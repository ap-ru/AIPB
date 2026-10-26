package com.aipb.context;

import com.aipb.model.Layout;
import com.aipb.semantic.Dimension;
import com.aipb.semantic.Model;
import com.aipb.semantic.SemanticModel;
import com.aipb.semantic.SemanticQuery;
import com.aipb.semantic.Window;
import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Turns what a page shows into the context a widget runs in. Three layers, later ones winning:
 * <ol>
 *   <li>the page: its entity (the client picker), its period slot's default, no filters;</li>
 *   <li>the request (the page's URL): {@code clientId}, {@code period} (an option id), one value list per filter
 *       dimension ({@code region=EMEA,APAC});</li>
 *   <li>the placement's own bindings ({@code config.params}, {@code config.bindings}): e.g. a trend pinned to 24 months
 *       whatever the page says.</li>
 * </ol>
 * This is the one place page context is interpreted, so the browser only forwards what is in the URL.
 */
@Service
public class ContextResolver {

    /** The context a widget runs in: the selected client (or null), the period window (or null) and the filters. */
    public record Resolved(String entityId, Window period, String periodLabel, List<SemanticQuery.Filter> filters) {
        public static final Resolved NONE = new Resolved(null, null, null, List.of());
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final SemanticModel semantic;
    private final SourceReader source;

    public ContextResolver(SemanticModel semantic, SourceReader source) {
        this.semantic = semantic;
        this.source = source;
    }

    /** The context for a placed widget on {@code page}, from the request's values and the placement's config. */
    @SuppressWarnings("unchecked")
    public Resolved resolve(Layout.Page page, Map<String, Object> config, Map<String, String> request) {
        Map<String, String> req = request == null ? Map.of() : request;
        Map<String, Object> cfg = config == null ? Map.of() : config;
        Map<String, Object> fixed = cfg.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> bindings = cfg.get("bindings") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

        String entityParam = entityParam();
        String entity = str(fixed.get(entityParam));
        if (entity == null && page != null && page.param(entityParam) != null) entity = blank(req.get(entityParam));

        Layout.PageContext pc = page == null ? null : page.getContext();
        Window period = null;
        String periodLabel = null;
        String bound = str(bindings.get("period"));
        if (bound != null) {
            Layout.PeriodOption o = pc != null && pc.getPeriod() != null ? pc.getPeriod().option(bound) : null;
            period = Window.parseKey(o != null ? o.getWindow() : bound);
            periodLabel = o != null ? o.getLabel() : period.describe();
        } else if (pc != null && pc.getPeriod() != null) {
            Layout.PeriodSlot slot = pc.getPeriod();
            String id = blank(req.get("period"));
            Layout.PeriodOption o = id == null ? slot.option(slot.getDefaultOption()) : slot.option(id);
            if (o == null) throw new IllegalArgumentException("unknown period '" + id + "'; this page offers: "
                    + String.join(", ", slot.getOptions().stream().map(Layout.PeriodOption::getId).toList()));
            period = Window.parseKey(o.getWindow());
            periodLabel = o.getLabel();
        }

        Map<String, String> values = new LinkedHashMap<>();
        if (pc != null) for (Layout.FilterSlot f : pc.getFilters()) {
            String v = blank(req.get(f.getDimension()));
            if (v != null) values.put(f.getDimension(), v);
        }
        if (bindings.get("filters") instanceof Map<?, ?> bf) bf.forEach((k, v) -> {
            if (str(v) == null) values.remove(String.valueOf(k)); else values.put(String.valueOf(k), str(v));
        });
        return new Resolved(entity, period, periodLabel, filters(values));
    }

    /**
     * Context from bare request values, for a widget outside a page (the library preview, the workbench): {@code clientId},
     * {@code period} as a window key, and {@code filter.<dimension>} value lists.
     */
    public Resolved fromRequest(Map<String, String> request) {
        Map<String, String> req = request == null ? Map.of() : request;
        String period = blank(req.get("period"));
        Window w = period == null ? null : Window.parseKey(period);
        Map<String, String> values = new LinkedHashMap<>();
        req.forEach((k, v) -> { if (k.startsWith("filter.") && blank(v) != null) values.put(k.substring(7), v); });
        return new Resolved(blank(req.get(entityParam())), w, w == null ? null : w.describe(), filters(values));
    }

    /** Filters from dimension -> "v1,v2": one value is eq, several are in. Unknown dimensions are refused. */
    public List<SemanticQuery.Filter> filters(Map<String, String> values) {
        List<SemanticQuery.Filter> out = new ArrayList<>();
        values.forEach((dim, raw) -> {
            if (semantic.dimension(dim) == null) throw new IllegalArgumentException("unknown filter dimension '" + dim + "'");
            List<String> vs = Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().toList();
            if (vs.isEmpty()) return;
            if (vs.size() == 1) out.add(new SemanticQuery.Filter(dim, "eq", TextNode.valueOf(vs.get(0))));
            else {
                ArrayNode arr = F.arrayNode();
                vs.forEach(arr::add);
                out.add(new SemanticQuery.Filter(dim, "in", arr));
            }
        });
        return out;
    }

    /**
     * The choices for a filter slot: the dimension's values when the semantic layer enumerates them, otherwise the
     * distinct values of the entity model's field (e.g. relationship managers), sorted.
     */
    public List<String> filterOptions(String dimension) {
        Dimension d = semantic.dimension(dimension);
        if (d == null || d.isTime()) throw new NoSuchElementException("no filterable dimension '" + dimension + "'");
        if (d.values() != null) return d.values();
        Model entity = semantic.entityModel();
        Model.Binding b = entity == null ? null : entity.dimensions().get(dimension);
        if (b == null || b.field().contains("."))
            throw new IllegalArgumentException("dimension '" + dimension + "' has no list of values to choose from");
        ArrayNode pipeline = F.arrayNode();
        pipeline.addObject().putObject("$group").put("_id", "$" + b.field());
        pipeline.addObject().putObject("$sort").put("_id", 1);
        List<String> out = new ArrayList<>();
        for (JsonNode n : source.aggregate(entity.collection(), pipeline).documents())
            if (n.hasNonNull("_id") && !n.get("_id").asText().isBlank()) out.add(n.get("_id").asText());
        return out;
    }

    public String entityParam() {
        Model e = semantic.entityModel();
        return e == null ? "clientId" : e.entity();
    }

    private static String str(Object o) { return o == null ? null : blank(String.valueOf(o)); }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
