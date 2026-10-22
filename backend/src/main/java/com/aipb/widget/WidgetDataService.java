package com.aipb.widget;

import com.aipb.context.ContextResolver;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.semantic.*;
import com.aipb.source.SourceData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Runs widget specs. The same path serves a placed widget at render time and the validator's preview, so what was
 * previewed is exactly what renders. A query spec goes through the semantic layer ({@link QueryExecutor}) in a page
 * context resolved by {@link ContextResolver} (client, period, filters); a custom spec's parameters are bound
 * ({@link ParamBinder}) and its pipeline run as written - it takes the client, but not the page's period or filters, and
 * says so. Either way the columns come back in one shape ({@link ResultSet.Column}).
 */
@Service
public class WidgetDataService {

    private final SourceData source;
    private final WidgetDefinitionRepository definitions;
    private final ObjectMapper mapper;
    private final QueryExecutor executor;
    private final ContextResolver contexts;
    private final LayoutService layouts;

    @Autowired
    public WidgetDataService(SourceData source, WidgetDefinitionRepository definitions, ObjectMapper mapper, QueryExecutor executor,
                             ContextResolver contexts, LayoutService layouts) {
        this.source = source;
        this.definitions = definitions;
        this.mapper = mapper;
        this.executor = executor;
        this.contexts = contexts;
        this.layouts = layouts;
    }

    /** Without page layouts: for tools and tests that run specs outside a page. */
    public WidgetDataService(SourceData source, WidgetDefinitionRepository definitions, ObjectMapper mapper, QueryExecutor executor) {
        this(source, definitions, mapper, executor, new ContextResolver(executor.model(), source), null);
    }

    /**
     * A widget's rows. {@code total} is how many rows the query produced (null if unknown); {@code truncated} if a source
     * limit cut them short; {@code notes} are the semantic layer's remarks (e.g. a current figure ignores the window).
     */
    public record DataResult(List<ResultSet.Column> columns, JsonNode rows, int count, Integer total, boolean truncated,
                             String asOfDate, Map<String, String> params, List<String> notes, ResultSet.Applied context) {}

    /** Runs a stored definition outside a page (request values: clientId, period window key, filter.<dimension>). */
    public DataResult data(String definitionId, Map<String, String> params) {
        return projected(spec(definitionId), contexts.fromRequest(params), params);
    }

    /**
     * Runs a widget placed on a page, in the page's context: {@code request} is what the page's URL holds (clientId,
     * period option, filter values); the placement's own bindings win over it.
     */
    public Placed placed(String layoutId, String pageId, String widgetId, Map<String, String> request) {
        Layout.Page page = layouts.get(layoutId).page(pageId);
        if (page == null) throw new NoSuchElementException("page " + pageId);
        Layout.Widget w = page.getWidgets().stream().filter(x -> x.getId().equals(widgetId)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("widget " + widgetId + " on page " + pageId));
        Object def = w.getConfig() == null ? null : w.getConfig().get("definitionId");
        if (def == null) throw new IllegalArgumentException("widget " + widgetId + " is not a library widget");
        String id = String.valueOf(def);
        return new Placed(id, projected(spec(id), contexts.resolve(page, w.getConfig(), request), request));
    }

    /** A placed widget's data and the library definition it renders. */
    public record Placed(String definitionId, DataResult data) {}

    private DataResult projected(WidgetSpec spec, ContextResolver.Resolved ctx, Map<String, String> params) {
        DataResult raw = execute(spec, ctx, params == null ? null : params.get(ParamBinder.AS_OF_DATE));
        return new DataResult(raw.columns(), project(raw.rows(), raw.columns(), Integer.MAX_VALUE), raw.count(), raw.total(),
                raw.truncated(), raw.asOfDate(), raw.params(), raw.notes(), raw.context());
    }

    /** Runs a spec with request values (clientId, period window key, filter.<dimension>, asOfDate). Rows are not projected. */
    public DataResult execute(WidgetSpec spec, Map<String, String> params) {
        return execute(spec, contexts.fromRequest(params), params == null ? null : params.get(ParamBinder.AS_OF_DATE));
    }

    /** Runs a spec in a resolved context; {@code asOfDate} (optional) replaces the data's today. Rows are not projected. */
    public DataResult execute(WidgetSpec spec, ContextResolver.Resolved ctx, String asOfDate) {
        if (spec.isQuery()) return executeQuery(spec, ctx, asOfDate);
        Map<String, String> params = new LinkedHashMap<>();
        if (ctx.entityId() != null) params.put(entityParam(), ctx.entityId());
        if (blankToNull(asOfDate) != null) params.put(ParamBinder.AS_OF_DATE, asOfDate);
        ParamBinder.Bound bound = ParamBinder.bind(spec, params, source::asOfDate);
        // sent as a JSON tree: converting to Maps would drop nulls such as {"$group": {"_id": null}}
        SourceData.Result r = source.aggregate(spec.custom().collection(), (ArrayNode) bound.pipeline());
        List<String> notes = new ArrayList<>();
        if (ctx.period() != null || !ctx.filters().isEmpty())
            notes.add("This widget's query is hand-written, so it does not follow the page's period or filters.");
        return new DataResult(customColumns(spec), r.documents(), r.count(), r.total(), r.truncated(), source.asOfDate(), bound.values(),
                notes, null);
    }

    private DataResult executeQuery(WidgetSpec spec, ContextResolver.Resolved ctx, String asOfDate) {
        String entityParam = entityParam();
        String entity = ctx.entityId();
        if ("entity".equals(spec.data().query().scopeOrDefault()) && entity == null)
            throw new IllegalArgumentException("missing parameter " + entityParam + " - widget '" + spec.id() + "' shows one client and needs "
                    + entityParam + " (string)");
        String asOfIso = blankToNull(asOfDate) != null ? ParamBinder.isoDate(ParamBinder.AS_OF_DATE, asOfDate.trim()) : source.asOfDate();
        LocalDate asOf = LocalDate.ofInstant(Instant.parse(asOfIso), ZoneOffset.UTC);
        boolean scoped = entity != null && !"book".equals(spec.data().query().scopeOrDefault());
        ResultSet r = executor.execute(spec.data().query(), new QueryContext(asOf, scoped ? entity : null, ctx.period(), ctx.filters()));
        Map<String, String> used = new LinkedHashMap<>();
        if (scoped) used.put(entityParam, entity);
        return new DataResult(applyOverrides(r.columns(), spec), r.rows(), r.count(), r.hasMore() ? null : r.count(), r.truncated(),
                r.asOfDate(), used, r.notes(), r.context());
    }

    /**
     * A spec's columns without running it: declared ones for custom data; for a query, the semantic layer's derived
     * columns with the spec's overrides. Throws {@link SemanticQueryException} for a query the layer cannot answer.
     */
    public List<ResultSet.Column> columns(WidgetSpec spec) {
        if (!spec.isQuery()) return customColumns(spec);
        QueryContext ctx = new QueryContext(LocalDate.ofInstant(Instant.parse(safeAsOf()), ZoneOffset.UTC),
                "entity".equals(spec.data().query().scopeOrDefault()) ? "sample" : null);
        QueryPlanner.Plan plan = executor.planner().plan(spec.data().query(), ctx);
        List<ResultSet.Column> cols = new ArrayList<>(plan.dimColumns());
        plan.outputs().forEach(o -> cols.add(o.column()));
        cols.addAll(plan.fieldColumns());
        return applyOverrides(cols, spec);
    }

    /** The page parameters a spec needs: a custom spec's declared params (but asOfDate); a query's client when its scope is entity. */
    public List<String> requiredParams(WidgetSpec spec) {
        if (spec.isQuery()) return "entity".equals(spec.data().query().scopeOrDefault()) ? List.of(entityParam()) : List.of();
        return spec.paramsOrEmpty().stream().map(WidgetSpec.Param::name).filter(n -> !n.equals(ParamBinder.AS_OF_DATE)).toList();
    }

    /** The parameters a spec can use from a page: its required ones, plus the client for an inherit-scope query. */
    public List<String> usableParams(WidgetSpec spec) {
        if (spec.isQuery() && !"book".equals(spec.data().query().scopeOrDefault())) return List.of(entityParam());
        return requiredParams(spec);
    }

    /** What a spec reads, for listings: a custom spec's collection; a query's models (records: its model). */
    public String sourceLabel(WidgetSpec spec) {
        if (!spec.isQuery()) return spec.custom().collection();
        SemanticQuery q = spec.data().query();
        if (q.records()) return q.model();
        Set<String> models = new LinkedHashSet<>();
        SemanticModel sm = executor.model();
        for (String m : q.metricsOrEmpty()) collectModels(sm, m, models);
        return String.join("+", models);
    }

    /** The page parameter that carries the selected client: the entity model's key (clientId). */
    public String entityParam() { return contexts.entityParam(); }

    /** The stored spec of a definition, in the current format. */
    public WidgetSpec spec(String definitionId) {
        WidgetDefinition d = definitions.findById(definitionId)
                .orElseThrow(() -> new NoSuchElementException("widget definition " + definitionId));
        return parse(d.getSpec());
    }

    /** The choices for a page parameter: [{value, label}] from its source collection, sorted by label. */
    public List<Map<String, String>> options(Layout.PageParam p) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put(p.getValueField(), 1);
        projection.put(p.getLabelField(), 1);
        JsonNode docs = source.find(p.getCollection(), null, projection, Map.of(p.getLabelField(), 1), 1000).documents();
        List<Map<String, String>> out = new ArrayList<>();
        docs.forEach(d -> out.add(Map.of("value", d.path(p.getValueField()).asText(), "label", d.path(p.getLabelField()).asText())));
        return out;
    }

    /** Keeps the given columns of each row (absent ones as null), at most {@code max} rows. */
    static ArrayNode project(JsonNode rows, List<ResultSet.Column> columns, int max) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        int n = 0;
        for (JsonNode row : rows) {
            if (n++ >= max) break;
            ObjectNode o = out.addObject();
            columns.forEach(c -> o.set(c.name(), row.has(c.name()) ? row.get(c.name()) : o.nullNode()));
        }
        return out;
    }

    WidgetSpec parse(String json) {
        try {
            return SpecMigrator.read(mapper, mapper.readTree(json));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored widget spec is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    // ------------------------------------------------------------------ helpers
    private static List<ResultSet.Column> customColumns(WidgetSpec spec) {
        List<ResultSet.Column> out = new ArrayList<>();
        for (WidgetSpec.Column c : spec.custom().columns())
            out.add(new ResultSet.Column(c.name(), c.label(), c.type(), c.unit(), c.formatOrDefault(), null, "field", null, c.tones(),
                    null, null, null));
        return out;
    }

    /** A query spec's label and tone changes, applied to the derived columns (unknown names are the validator's concern). */
    private static List<ResultSet.Column> applyOverrides(List<ResultSet.Column> cols, WidgetSpec spec) {
        if (!spec.isQuery() || spec.data().overridesOrEmpty().isEmpty()) return cols;
        Map<String, WidgetSpec.Column> byName = new HashMap<>();
        spec.data().overridesOrEmpty().forEach(o -> byName.put(o.name(), o));
        List<ResultSet.Column> out = new ArrayList<>();
        for (ResultSet.Column c : cols) {
            WidgetSpec.Column o = byName.get(c.name());
            if (o != null && o.label() != null) c = c.withLabel(o.label());
            if (o != null && o.tones() != null && !o.tones().isEmpty()) c = c.withTones(o.tones());
            out.add(c);
        }
        return out;
    }

    private static void collectModels(SemanticModel sm, String metric, Set<String> out) {
        if (sm.measure(metric) != null) { out.add(sm.measure(metric).model().id()); return; }
        DerivedMetric d = sm.derivedMetric(metric);
        if (d == null) return;
        for (DerivedMetric.Operand o : d.operands()) collectOperand(sm, o, out);
    }

    private static void collectOperand(SemanticModel sm, DerivedMetric.Operand o, Set<String> out) {
        if (o instanceof DerivedMetric.Sum s) s.of().forEach(p -> collectOperand(sm, p, out));
        else collectModels(sm, ((DerivedMetric.Ref) o).metric(), out);
    }

    private String safeAsOf() {
        try { return source.asOfDate(); } catch (RuntimeException e) { return Instant.now().toString(); }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
