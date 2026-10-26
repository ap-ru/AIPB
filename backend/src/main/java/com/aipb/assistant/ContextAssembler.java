package com.aipb.assistant;

import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceData;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetDefinition;
import com.aipb.widget.WidgetDefinitionRepository;
import com.aipb.widget.WidgetPrimitives;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Decides exactly what each stage of the assistant sees. Static blocks (primer, stage rules, data dictionary, design
 * catalog, worked examples) go in the system prompt: they are generated deterministically, so they are byte-identical
 * across calls and sessions and served from the prompt cache. Dynamic blocks (the page, the data's today, the library,
 * the brief) go in the user message. Every block is listed in a manifest (name, source, size, hash) stored with the
 * stage run, so what the model saw can always be inspected.
 */
@Component
public class ContextAssembler {

    static final List<String> BUILD_EXAMPLES = List.of("client-revenue-ytd", "client-revenue-trend", "client-covenants");
    static final List<String> PRESENT_EXAMPLES = List.of("client-exposure", "client-revenue-by-lob", "book-revenue-by-lob-monthly", "client-active-signals");
    private static final long CATALOG_TTL_MS = 10 * 60_000;
    /** Roles the design system has but the assistant's Present stage cannot fill yet (its design schema has no slot for them). */
    static final Set<String> NOT_FOR_ASSISTANT = Set.of("series");

    /** A piece of context: what it is, where it came from, and its text. */
    public record Block(String name, String source, String text) {}

    /** One manifest line: what the model saw, without the text itself. */
    public record ManifestEntry(String block, String source, int chars, String sha256) {}

    private final SourceData source;
    private final WidgetPrimitives primitives;
    private final WidgetDefinitionRepository library;
    private final WidgetDataService widgetData;
    private final LayoutService layouts;
    private final ObjectMapper mapper;
    private final Map<String, String> prompts = new HashMap<>();
    private volatile JsonNode catalog;
    private volatile long catalogAt;

    public ContextAssembler(SourceData source, WidgetPrimitives primitives, WidgetDefinitionRepository library,
                            WidgetDataService widgetData, LayoutService layouts, ObjectMapper mapper) {
        this.source = source;
        this.primitives = primitives;
        this.library = library;
        this.widgetData = widgetData;
        this.layouts = layouts;
        this.mapper = mapper;
        for (String p : List.of("primer", "intake", "build", "review", "present")) prompts.put(p, resource("assistant/prompts/" + p + ".md"));
    }

    // ------------------------------------------------------------------ static context, per stage
    public List<Block> intakeSystem() {
        JsonNode c = catalog();
        return List.of(primer(), stage("intake"),
                new Block("data-overview", "_catalog/_database", CatalogRenderer.overview(c)),
                new Block("data-index", "_catalog (generated)", CatalogRenderer.index(c)),
                new Block("primitive-menu", "widgets/primitives.json", primitiveMenu()));
    }

    public List<Block> buildSystem() {
        JsonNode c = catalog();
        return List.of(primer(), stage("build"),
                new Block("data-overview", "_catalog/_database", CatalogRenderer.overview(c)),
                new Block("data-dictionary", "_catalog (generated)", CatalogRenderer.dictionary(c)),
                new Block("build-examples", "assistant/examples " + BUILD_EXAMPLES, buildExamples()));
    }

    public List<Block> reviewSystem() {
        JsonNode c = catalog();
        return List.of(primer(), stage("review"),
                new Block("data-overview", "_catalog/_database", CatalogRenderer.overview(c)),
                new Block("data-dictionary", "_catalog (generated)", CatalogRenderer.dictionary(c)));
    }

    public List<Block> presentSystem() {
        return List.of(primer(), stage("present"),
                new Block("design-catalog", "widgets/primitives.json", designCatalog()),
                new Block("present-examples", "assistant/examples " + PRESENT_EXAMPLES, presentExamples()));
    }

    // ------------------------------------------------------------------ dynamic context
    /** The page the widget is for: scope, parameters (with the banker's current selection), existing widgets. */
    public String page(String layoutId, String pageId, Map<String, String> params) {
        return page(layoutId, pageId, params, true);
    }

    /** As {@link #page(String, String, Map)}; {@code withWidgets} false leaves out the page's widgets (noise when writing queries). */
    public String page(String layoutId, String pageId, Map<String, String> params, boolean withWidgets) {
        Layout.Page page = layouts.get(layoutId).page(pageId);
        if (page == null) throw new NoSuchElementException("page " + pageId);
        StringBuilder sb = new StringBuilder("<page>\n");
        sb.append("id: ").append(page.getId()).append("\ntitle: ").append(page.getTitle()).append('\n');
        if (page.getDescription() != null) sb.append("description: ").append(page.getDescription()).append('\n');
        if (page.getParams() == null || page.getParams().isEmpty()) {
            sb.append("parameters: none - widgets on this page are book-wide (all clients)\n");
        } else {
            sb.append("parameters:\n");
            for (Layout.PageParam p : page.getParams()) {
                String v = params == null ? null : params.get(p.getName());
                sb.append("- ").append(p.getName()).append(" (").append(p.getLabel()).append(", from ").append(p.getCollection())
                        .append('.').append(p.getValueField()).append(')');
                if (v != null) sb.append(" - currently selected: ").append(label(p, v)).append(" (").append(v)
                        .append("); queries are tested with this value");
                sb.append('\n');
            }
        }
        if (!withWidgets) return sb.append("</page>\n").toString();
        sb.append("existing widgets, in order:\n");
        if (page.getWidgets().isEmpty()) sb.append("- (none)\n");
        for (Layout.Widget w : page.getWidgets()) {
            sb.append("- ").append(w.getTitle()).append(" (").append(w.getType());
            Object def = w.getConfig() == null ? null : w.getConfig().get("definitionId");
            if (def != null) sb.append(' ').append(def);
            sb.append(", span ").append(w.getSpan()).append(")\n");
        }
        return sb.append("</page>\n").toString();
    }

    /** The data's today, and what it implies for revenue. */
    public String dataToday() {
        String asOf = source.asOfDate().substring(0, 10);
        String lastMonth = java.time.LocalDate.parse(asOf).withDayOfMonth(1).minusMonths(1).toString().substring(0, 7);
        return "<data_today>asOfDate: " + asOf + " (the latest closed revenue month is " + lastMonth + ")</data_today>\n";
    }

    /** The widget library the session's owner can see (global plus their personal widgets), so the assistant can reuse rather than duplicate. */
    public String library(String ownerId) {
        StringBuilder sb = new StringBuilder("<library>\n");
        List<WidgetDefinition> defs = new ArrayList<>(library.findAll().stream().filter(d -> d.visibleTo(ownerId)).toList());
        defs.sort(Comparator.comparing(WidgetDefinition::getId));
        for (WidgetDefinition d : defs) {
            sb.append("- ").append(d.getId()).append(": \"").append(d.getTitle()).append("\" (").append(d.getPrimitive())
                    .append(followsPage(d) ? "; the selected client, or the whole book on a page without one"
                            : d.getParams() == null || d.getParams().isEmpty() ? "; book-wide" : "; needs " + String.join(", ", d.getParams()))
                    .append(") - ").append(d.getDescription()).append('\n');
        }
        return sb.append("</library>\n").toString();
    }

    /** A semantic-query definition whose scope is inherit: it shows the page's client, or the book where there is none. */
    private boolean followsPage(WidgetDefinition d) {
        try {
            JsonNode q = mapper.readTree(d.getSpec() == null ? "{}" : d.getSpec()).path("data").path("query");
            return q.isObject() && q.path("scope").asText("inherit").equals("inherit");
        } catch (IOException e) {
            return false;
        }
    }

    /** Field and derived-metric names per collection, for checking catalogReference values. */
    public Map<String, Set<String>> referenceNames() { return CatalogRenderer.referenceNames(catalog()); }

    public List<ManifestEntry> manifest(List<Block> system, String userContext) {
        List<ManifestEntry> out = new ArrayList<>();
        system.forEach(b -> out.add(new ManifestEntry(b.name(), b.source(), b.text().length(), sha256(b.text()))));
        if (userContext != null) out.add(new ManifestEntry("user-context", "session (dynamic)", userContext.length(), sha256(userContext)));
        return out;
    }

    public static List<String> texts(List<Block> blocks) { return blocks.stream().map(Block::text).toList(); }

    // ------------------------------------------------------------------ renderers
    private Block primer() { return new Block("primer", "assistant/prompts/primer.md", prompts.get("primer")); }

    private Block stage(String name) { return new Block(name + "-rules", "assistant/prompts/" + name + ".md", prompts.get(name)); }

    private String primitiveMenu() {
        StringBuilder sb = new StringBuilder("# Display primitives\n\nWidgets are drawn with one of these:\n");
        primitives.catalog().path("primitives").fields().forEachRemaining(e ->
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().path("description").asText()).append('\n'));
        return sb.toString();
    }

    private String designCatalog() {
        JsonNode cat = primitives.catalog();
        StringBuilder sb = new StringBuilder("# Design system: primitives\n\n").append(cat.path("description").asText()).append('\n');
        if (cat.has("intents")) {
            sb.append("\n## Design intents\nSet only those a primitive lists as an option or role; null otherwise.\n");
            cat.path("intents").fields().forEachRemaining(i -> sb.append("- ").append(i.getKey()).append(": ").append(i.getValue().asText()).append('\n'));
        }
        cat.path("primitives").fields().forEachRemaining(e -> {
            JsonNode p = e.getValue();
            sb.append("\n## ").append(e.getKey()).append('\n').append(p.path("description").asText()).append('\n').append("Roles:\n");
            p.path("roles").fields().forEachRemaining(r -> {
                if (NOT_FOR_ASSISTANT.contains(r.getKey())) return;
                JsonNode d = r.getValue();
                sb.append("- ").append(r.getKey()).append(d.path("required").asBoolean(false) ? " (required" : " (optional")
                        .append(d.path("many").asBoolean(false) ? ", list of columns" : ", one column").append("; types: ")
                        .append(String.join("/", strings(d.path("types")))).append(')');
                if (d.has("description")) sb.append(": ").append(d.get("description").asText());
                sb.append('\n');
            });
            if (p.has("options")) {
                p.path("options").fields().forEachRemaining(o -> sb.append("- ").append(o.getKey()).append(" (option): ")
                        .append(o.getValue().isArray() ? String.join(" | ", strings(o.getValue())) : o.getValue().asText()).append('\n'));
            }
            JsonNode rows = p.path("rows");
            sb.append("Rows: ").append(rows.path("min").asInt(0)).append(" to ").append(rows.path("max").asInt())
                    .append(rows.has("warn") ? " (more than " + rows.get("warn").asInt() + " is a lot)" : "")
                    .append(p.has("rowsWithX") ? "; with x: " + p.path("rowsWithX").path("min").asInt() + " to " + p.path("rowsWithX").path("max").asInt() : "")
                    .append(". Default span: ").append(p.path("defaultSpan").asInt()).append('\n')
                    .append("Guidance: ").append(p.path("guidance").asText()).append('\n');
        });
        sb.append("\n## Badge tones\ngood (green), warn (amber), bad (red), info (blue), neutral (grey). Badges always show their text.\n");
        return sb.toString();
    }

    private String buildExamples() {
        StringBuilder sb = new StringBuilder("# Worked examples\n\nData definitions of existing, verified widgets. "
                + "They show the conventions; build what your brief asks, not these.\n");
        for (String id : BUILD_EXAMPLES) {
            JsonNode s = starter(id);
            ObjectNode data = mapper.createObjectNode();
            data.set("params", s.path("params"));
            data.set("collection", s.path("data").path("collection"));
            data.set("pipeline", s.path("data").path("pipeline"));
            data.set("columns", s.path("data").path("columns"));
            sb.append("\n## ").append(s.path("title").asText()).append("\nBrief: ").append(s.path("description").asText())
                    .append("\n```json\n").append(pretty(data)).append("\n```\n");
        }
        return sb.toString();
    }

    private String presentExamples() {
        StringBuilder sb = new StringBuilder("# Worked examples\n\nHow existing widgets display their data.\n");
        for (String id : PRESENT_EXAMPLES) {
            JsonNode s = starter(id);
            ObjectNode cols = mapper.createObjectNode();
            s.path("data").path("columns").forEach(c -> cols.put(c.path("name").asText(),
                    c.path("type").asText() + (c.has("unit") ? ", " + c.path("unit").asText() : "")));
            ObjectNode out = mapper.createObjectNode();
            out.put("title", s.path("title").asText());
            out.put("description", s.path("description").asText());
            out.set("view", s.path("view"));
            out.set("span", s.path("layout").path("span"));
            sb.append("\n## ").append(s.path("title").asText()).append("\nColumns: ").append(cols)
                    .append("\n```json\n").append(pretty(out)).append("\n```\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers
    JsonNode catalog() {
        JsonNode c = catalog;
        if (c == null || System.currentTimeMillis() - catalogAt > CATALOG_TTL_MS) {
            c = source.catalog();
            catalog = c;
            catalogAt = System.currentTimeMillis();
        }
        return c;
    }

    private String label(Layout.PageParam p, String value) {
        try {
            return widgetData.options(p).stream().filter(o -> value.equals(o.get("value"))).map(o -> o.get("label")).findFirst().orElse(value);
        } catch (RuntimeException e) {
            return value;
        }
    }

    /**
     * A worked example: a frozen copy (resources/assistant/examples) of a starter widget as it was written by hand, as a
     * pipeline. The Build stage writes pipelines, so it learns from these rather than from today's starters, which are
     * semantic queries; the examples are retired when the assistant moves onto the semantic layer.
     */
    private JsonNode starter(String id) {
        try (InputStream in = new ClassPathResource("assistant/examples/" + id + ".json").getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("missing assistant example " + id, e);
        }
    }

    private String pretty(JsonNode n) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", d[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
