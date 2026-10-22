package com.aipb.assistant;

import com.aipb.assistant.llm.LlmRequest;
import com.aipb.source.SourceData;
import com.aipb.source.SourceException;
import com.aipb.widget.ParamBinder;
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
import java.util.regex.Pattern;

/**
 * The assistant's read-only data tools. Every call goes through {@link SourceData} (pinned to the source database,
 * capped by the MCP server) and every input is validated here - tools that carry free-form pipelines are not
 * schema-strict. Outputs are shaped to stay small: a few rows, long values cut, so tool results don't swamp the
 * model's context.
 */
@Component
public class AssistantTools {

    static final int MAX_ROWS = 20;
    static final int MAX_SAMPLE = 5;
    static final int MAX_DISTINCT = 50;
    static final int MAX_STRING = 200;
    static final int MAX_ARRAY = 5;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]{0,99}");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final SourceData source;
    private final ObjectMapper mapper;
    private final Map<String, LlmRequest.ToolDef> defs = new TreeMap<>();

    /** Where a tool runs: the page, and the sample parameter values queries are tested with. */
    public record Context(String layoutId, String pageId, Map<String, String> sampleParams) {}

    /** A tool's answer: the content returned to the model, whether it is an error, and a one-line summary for the banker. */
    public record Outcome(String content, boolean isError, String summary) {}

    public AssistantTools(SourceData source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
        for (String name : List.of("distinct_values", "run_query", "sample_documents", "submit_data", "submit_review")) {
            JsonNode d = read("assistant/tools/" + name + ".json");
            defs.put(name, new LlmRequest.ToolDef(name, d.path("description").asText(), d.path("input_schema"), d.path("strict").asBoolean(false)));
        }
    }

    /** Tool definitions by name, sorted (a deterministic tool list keeps the prompt prefix cacheable). */
    public List<LlmRequest.ToolDef> definitions(String... names) {
        return Arrays.stream(names).sorted().map(defs::get).toList();
    }

    // ------------------------------------------------------------------ tools
    public Outcome sampleDocuments(JsonNode in, Context ctx) {
        try {
            String coll = text(in, "collection");
            int limit = Math.max(1, Math.min(MAX_SAMPLE, in.path("limit").asInt(3)));
            Map<String, Object> filter = in.has("filter") && in.get("filter").isObject() ? map(in.get("filter")) : null;
            Map<String, Object> projection = null;
            if (in.path("fields").isArray() && in.get("fields").size() > 0) {
                projection = new LinkedHashMap<>();
                for (JsonNode f : in.get("fields")) projection.put(field(f.asText()), 1);
            }
            SourceData.Result r = source.find(coll, filter, projection, null, limit);
            ObjectNode out = F.objectNode();
            out.put("returned", r.count());
            out.set("documents", trim(r.documents(), 0));
            return new Outcome(out.toString(), false, "Looked at " + r.count() + " sample " + coll + " document" + (r.count() == 1 ? "" : "s"));
        } catch (IllegalArgumentException | SourceException e) {
            return error("sample_documents", e);
        }
    }

    public Outcome distinctValues(JsonNode in, Context ctx) {
        try {
            String coll = text(in, "collection");
            String field = field(text(in, "field"));
            ArrayNode pipeline = F.arrayNode();
            pipeline.addObject().set("$match", in.has("filter") && in.get("filter").isObject() ? in.get("filter") : F.objectNode());
            pipeline.addObject().set("$group", F.objectNode().put("_id", "$" + field).set("count", F.objectNode().put("$sum", 1)));
            pipeline.addObject().set("$sort", F.objectNode().put("count", -1).put("_id", 1));
            pipeline.addObject().put("$limit", MAX_DISTINCT);
            SourceData.Result r = source.aggregate(coll, pipeline);
            ArrayNode values = F.arrayNode();
            r.documents().forEach(d -> values.addObject().<ObjectNode>set("value", trim(d.get("_id"), 0)).put("count", d.path("count").asLong()));
            ObjectNode out = F.objectNode();
            out.put("field", coll + "." + field);
            out.set("values", values);
            if (r.count() >= MAX_DISTINCT) out.put("note", "only the " + MAX_DISTINCT + " most frequent values are shown");
            return new Outcome(out.toString(), false, "Checked " + values.size() + " distinct value" + (values.size() == 1 ? "" : "s") + " of " + coll + "." + field);
        } catch (IllegalArgumentException | SourceException e) {
            return error("distinct_values", e);
        }
    }

    public Outcome runQuery(JsonNode in, Context ctx) {
        try {
            String coll = text(in, "collection");
            ArrayNode pipeline = pipeline(in);
            WidgetSpec spec = provisional(coll, pipeline, in.path("params"));
            ParamBinder.Bound bound = ParamBinder.bind(spec, ctx.sampleParams(), source::asOfDate);
            SourceData.Result r = source.aggregate(coll, (ArrayNode) bound.pipeline());
            ObjectNode out = F.objectNode();
            out.put("rowCount", r.count());
            if (r.total() != null) out.put("totalMatched", r.total());
            out.put("cutOffByServerLimit", r.truncated());
            out.set("sampleParams", mapper.valueToTree(bound.values()));
            Set<String> fields = new LinkedHashSet<>();
            r.documents().forEach(d -> d.fieldNames().forEachRemaining(fields::add));
            out.set("fields", mapper.valueToTree(fields));
            ArrayNode rows = F.arrayNode();
            for (int i = 0; i < Math.min(MAX_ROWS, r.count()); i++) rows.add(trim(r.documents().get(i), 0));
            out.set("rows", rows);
            if (r.count() > MAX_ROWS) out.put("note", "showing the first " + MAX_ROWS + " of " + r.count() + " rows");
            if (pipeline.toString().contains("$$NOW")) out.put("warning", "$$NOW is the wall clock; widgets must use {\"$param\": \"asOfDate\"}");
            return new Outcome(out.toString(), false, "Ran a query on " + coll + ": " + r.count() + " row" + (r.count() == 1 ? "" : "s"));
        } catch (IllegalArgumentException | SourceException e) {
            return error("run_query", e);
        }
    }

    // ------------------------------------------------------------------ shared helpers (also used by the gates)
    /** A spec carrying just enough (params + pipeline) for {@link ParamBinder}. */
    WidgetSpec provisional(String collection, ArrayNode pipeline, JsonNode params) {
        ObjectNode s = F.objectNode().put("id", "query").put("title", "query").put("description", "query");
        s.set("params", params != null && params.isArray() ? params : F.arrayNode());
        ObjectNode data = s.putObject("data");
        data.put("collection", collection);
        data.set("pipeline", pipeline);
        data.putArray("columns");
        s.putObject("view").put("primitive", "table");
        return com.aipb.widget.SpecMigrator.read(mapper, s);
    }

    static ArrayNode pipeline(JsonNode in) {
        JsonNode p = in.get("pipeline");
        if (p == null || !p.isArray() || p.isEmpty()) throw new IllegalArgumentException("pipeline must be a non-empty array of stages");
        for (JsonNode stage : p) if (!stage.isObject() || stage.size() != 1)
            throw new IllegalArgumentException("each pipeline stage must be an object with exactly one operator, e.g. {\"$match\": {...}}; got " + stage);
        return (ArrayNode) p;
    }

    static String text(JsonNode in, String key) {
        JsonNode v = in.get(key);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new IllegalArgumentException(key + " is required");
        return v.asText();
    }

    static String field(String f) {
        if (!FIELD.matcher(f).matches()) throw new IllegalArgumentException("'" + f + "' is not a valid field name");
        return f;
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
