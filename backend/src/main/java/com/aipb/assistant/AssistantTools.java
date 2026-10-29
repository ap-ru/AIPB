package com.aipb.assistant;

import com.aipb.assistant.llm.LlmRequest;
import com.aipb.source.GraphMcpSource;
import com.aipb.source.SourceReader;
import com.aipb.source.SourceException;
import com.aipb.widget.ParamBinder;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * The assistant's read-only data tools. Every call goes through {@link GraphMcpSource} (pinned to the bank graph,
 * checked read-only before it is sent, capped in records and time) and every input is validated here - tools that
 * carry free-form Cypher are not schema-strict. Outputs are shaped to stay small: a few rows, long values cut, so tool
 * results don't swamp the model's context.
 */
@Component
public class AssistantTools {

    static final int MAX_ROWS = 20;
    static final int MAX_SAMPLE = 5;
    static final int MAX_DISTINCT = 50;
    static final int MAX_STRING = 200;
    static final int MAX_ARRAY = 5;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final GraphMcpSource source;
    private final ObjectMapper mapper;
    private final Map<String, LlmRequest.ToolDef> defs = new TreeMap<>();

    /** Where a tool runs: the page, and the sample parameter values queries are tested with. */
    public record Context(String layoutId, String pageId, Map<String, String> sampleParams) {}

    /** A tool's answer: the content returned to the model, whether it is an error, and a one-line summary for the banker. */
    public record Outcome(String content, boolean isError, String summary) {}

    public AssistantTools(GraphMcpSource source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
        for (String name : List.of("distinct_values", "graph_schema", "run_query", "sample_nodes", "submit_data", "submit_review")) {
            JsonNode d = read("assistant/tools/" + name + ".json");
            defs.put(name, new LlmRequest.ToolDef(name, d.path("description").asText(), d.path("input_schema"), d.path("strict").asBoolean(false)));
        }
    }

    /** Tool definitions by name, sorted (a deterministic tool list keeps the prompt prefix cacheable). */
    public List<LlmRequest.ToolDef> definitions(String... names) {
        return Arrays.stream(names).sorted().map(defs::get).toList();
    }

    // ------------------------------------------------------------------ tools
    public Outcome sampleNodes(JsonNode in, Context ctx) {
        try {
            String label = SourceReader.label(text(in, "label"));
            int limit = Math.max(1, Math.min(MAX_SAMPLE, in.path("limit").asInt(3)));
            Map<String, Object> filter = in.has("filter") && in.get("filter").isObject() ? map(in.get("filter")) : null;
            if (filter != null) filter.keySet().forEach(SourceReader::property);
            List<String> fields = null;
            if (in.path("properties").isArray() && in.get("properties").size() > 0) {
                fields = new ArrayList<>();
                for (JsonNode f : in.get("properties")) fields.add(SourceReader.property(f.asText()));
            }
            SourceReader.Result r = source.find(label, filter, fields, null, limit);
            ObjectNode out = F.objectNode();
            out.put("returned", r.count());
            out.set("nodes", trim(r.documents(), 0));
            return new Outcome(out.toString(), false, "Looked at " + r.count() + " sample " + label + " node" + (r.count() == 1 ? "" : "s"));
        } catch (IllegalArgumentException | SourceException e) {
            return error("sample_nodes", e);
        }
    }

    public Outcome distinctValues(JsonNode in, Context ctx) {
        try {
            String label = SourceReader.label(text(in, "label"));
            String prop = SourceReader.property(text(in, "property"));
            Map<String, Object> params = new LinkedHashMap<>();
            List<String> where = new ArrayList<>();
            if (in.has("filter") && in.get("filter").isObject()) {
                int i = 0;
                for (Iterator<Map.Entry<String, JsonNode>> it = in.get("filter").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    String p = "f" + i++;
                    where.add("n." + SourceReader.property(e.getKey()) + (e.getValue().isArray() ? " IN $" : " = $") + p);
                    params.put(p, e.getValue());
                }
            }
            String cypher = "MATCH (n:" + label + ")" + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                    + " RETURN n." + prop + " AS value, count(*) AS count ORDER BY count DESC, value LIMIT " + MAX_DISTINCT;
            SourceReader.Result r = source.cypher(cypher, params);
            ArrayNode values = F.arrayNode();
            r.documents().forEach(d -> values.addObject().<ObjectNode>set("value", trim(d.get("value"), 0)).put("count", d.path("count").asLong()));
            ObjectNode out = F.objectNode();
            out.put("property", label + "." + prop);
            out.set("values", values);
            if (r.count() >= MAX_DISTINCT) out.put("note", "only the " + MAX_DISTINCT + " most frequent values are shown");
            return new Outcome(out.toString(), false, "Checked " + values.size() + " distinct value" + (values.size() == 1 ? "" : "s") + " of " + label + "." + prop);
        } catch (IllegalArgumentException | SourceException e) {
            return error("distinct_values", e);
        }
    }

    public Outcome runQuery(JsonNode in, Context ctx) {
        try {
            String cypher = text(in, "cypher");
            WidgetSpec spec = provisional(cypher, in.path("params"));
            ParamBinder.Bound bound = ParamBinder.bind(spec, ctx.sampleParams(), source::asOfDate);
            SourceReader.Result r = source.cypher(cypher, bound.params());
            ObjectNode out = F.objectNode();
            out.put("rowCount", r.count());
            if (r.total() != null) out.put("totalMatched", r.total());
            out.put("cutOffByServerLimit", r.truncated());
            out.set("sampleParams", mapper.valueToTree(bound.values()));
            Set<String> fields = new LinkedHashSet<>();
            r.documents().forEach(d -> d.fieldNames().forEachRemaining(fields::add));
            out.set("columns", mapper.valueToTree(fields));
            ArrayNode rows = F.arrayNode();
            for (int i = 0; i < Math.min(MAX_ROWS, r.count()); i++) rows.add(trim(r.documents().get(i), 0));
            out.set("rows", rows);
            if (r.count() > MAX_ROWS) out.put("note", "showing the first " + MAX_ROWS + " of " + r.count() + " rows");
            List<String> labels = WidgetDataService.labels(cypher);
            return new Outcome(out.toString(), false, "Ran a query on " + (labels.isEmpty() ? "the graph" : String.join(", ", labels)) + ": "
                    + r.count() + " row" + (r.count() == 1 ? "" : "s"));
        } catch (IllegalArgumentException | SourceException e) {
            return error("run_query", e);
        }
    }

    /** What the graph holds for a label: its stored properties (with types) and its relationships, counted. */
    public Outcome graphSchema(JsonNode in, Context ctx) {
        try {
            String label = SourceReader.label(text(in, "label"));
            JsonNode schema = source.schema(label);
            return new Outcome(trim(schema, 0).toString(), false, "Looked at the graph schema of " + label);
        } catch (IllegalArgumentException | SourceException e) {
            return error("graph_schema", e);
        }
    }

    // ------------------------------------------------------------------ shared helpers (also used by the gates)
    /** A spec carrying just enough (params + Cypher) for {@link ParamBinder}. */
    WidgetSpec provisional(String cypher, JsonNode params) {
        ObjectNode s = F.objectNode().put("id", "query").put("specVersion", 2).put("title", "query").put("description", "query");
        s.set("params", params != null && params.isArray() ? params : F.arrayNode());
        ObjectNode custom = s.putObject("data").putObject("custom");
        custom.put("cypher", cypher);
        custom.putArray("columns");
        s.putObject("view").put("primitive", "table");
        return com.aipb.widget.SpecMigrator.read(mapper, s);
    }

    static String text(JsonNode in, String key) {
        JsonNode v = in.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException(key + " is required");
        return v.asText();
    }

    /** Cuts long strings and arrays, recursively, so outputs stay compact. */
    static JsonNode trim(JsonNode n, int depth) {
        if (n == null) return F.nullNode();
        if (n.isTextual() && n.asText().length() > MAX_STRING) return F.textNode(n.asText().substring(0, MAX_STRING) + "…");
        if (n.isArray()) {
            ArrayNode a = F.arrayNode();
            for (int i = 0; i < Math.min(MAX_ARRAY, n.size()); i++) a.add(trim(n.get(i), depth + 1));
            if (n.size() > MAX_ARRAY) a.add("… " + (n.size() - MAX_ARRAY) + " more");
            return a;
        }
        if (n.isObject()) {
            ObjectNode o = F.objectNode();
            n.fields().forEachRemaining(e -> o.set(e.getKey(), trim(e.getValue(), depth + 1)));
            return o;
        }
        return n;
    }

    private Map<String, Object> map(JsonNode n) {
        @SuppressWarnings("unchecked") Map<String, Object> m = mapper.convertValue(n, Map.class);
        return m;
    }

    private static Outcome error(String tool, Exception e) {
        return new Outcome("{\"error\": " + JsonNodeFactory.instance.textNode(String.valueOf(e.getMessage())) + "}", true,
                tool + " failed: " + e.getMessage());
    }

    private JsonNode read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
