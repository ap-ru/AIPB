package com.aipb.assistant.gates;

import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.widget.ValidationReport;
import com.aipb.widget.WidgetSpecValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Checks a submit_data submission before the assistant moves on. Deterministic, no model involved:
 * (a) the submission's shape; (b) every numeric column has a measure whose catalogReference names a real field or
 * derived metric in the data dictionary - invented definitions are caught here; (c) the full widget validator on a
 * provisional spec (the data shown as a table) with the page's sample parameters - schema, parameters, forbidden
 * operators, unit/format agreement, a live run, non-empty and non-truncated results, declared columns and types.
 */
@Component
@RequiredArgsConstructor
public class BuildGate {

    private final WidgetSpecValidator validator;
    private final ContextAssembler context;
    private final ObjectMapper mapper;

    /** {@code artifact} (the submission plus its preview and warnings) is set only when accepted. */
    public record Result(boolean accepted, List<String> errors, ObjectNode artifact) {}

    public Result check(JsonNode in, AssistantTools.Context ctx, String title, String description) {
        List<String> errors = new ArrayList<>();
        for (String key : List.of("params", "pipeline", "columns", "measures", "assumptions"))
            if (!in.path(key).isArray()) errors.add(key + " must be an array");
        if (!in.path("collection").isTextual()) errors.add("collection is required");
        if (!in.path("expectedShape").isTextual()) errors.add("expectedShape is required");
        if (!errors.isEmpty()) return new Result(false, errors, null);

        checkMeasures(in, errors);
        if (!errors.isEmpty()) return new Result(false, errors, null);

        ObjectNode spec = provisionalSpec(in, title, description);
        ValidationReport report = validator.validate(spec, ctx.layoutId(), ctx.pageId(), ctx.sampleParams());
        report.errors().forEach(e -> errors.add(e.code() + " (" + e.path() + "): " + e.message()));
        boolean emptyOk = in.path("emptyIsExpected").asBoolean(false);
        report.warnings().stream().filter(w -> w.code().equals("empty") && !emptyOk).forEach(w ->
                errors.add("empty: " + w.message() + " If an empty result is genuinely expected for this sample, set emptyIsExpected to true."));
        if (!errors.isEmpty()) return new Result(false, errors, null);

        ObjectNode artifact = ((ObjectNode) in).deepCopy();
        artifact.set("preview", mapper.valueToTree(report.preview()));
        artifact.set("warnings", mapper.valueToTree(report.warnings()));
        artifact.set("sampleParams", mapper.valueToTree(report.sampleParams()));
        return new Result(true, List.of(), artifact);
    }

    /** The submitted data as a spec, displayed as a table of all its columns - what the validator checks. */
    public ObjectNode provisionalSpec(JsonNode in, String title, String description) {
        ObjectNode spec = mapper.createObjectNode();
        spec.put("id", "assistant-draft");
        spec.put("title", truncate(title == null || title.isBlank() ? "Draft widget" : title, 80));
        spec.put("description", truncate(description == null || description.isBlank() ? "Draft widget" : description, 400));
        spec.set("params", in.get("params"));
        ObjectNode data = spec.putObject("data");
        data.set("collection", in.get("collection"));
        data.set("pipeline", in.get("pipeline"));
        data.set("columns", in.get("columns"));
        ObjectNode view = spec.putObject("view");
        view.put("primitive", "table");
        ArrayNode cols = view.putArray("columns");
        in.get("columns").forEach(c -> cols.add(c.path("name").asText()));
        return spec;
    }

    private void checkMeasures(JsonNode in, List<String> errors) {
        Map<String, Set<String>> refs = context.referenceNames();
        Map<String, JsonNode> measures = new HashMap<>();
        Set<String> columns = new HashSet<>();
        in.get("columns").forEach(c -> columns.add(c.path("name").asText()));
        for (JsonNode m : in.get("measures")) {
            String col = m.path("column").asText();
            if (!columns.contains(col)) errors.add("measure for '" + col + "' does not match any declared column");
            if (m.path("definition").asText("").isBlank()) errors.add("measure '" + col + "' needs a definition");
            String ref = m.path("catalogReference").asText("");
            int dot = ref.indexOf('.');
            String coll = dot > 0 ? ref.substring(0, dot) : ref;
            String name = dot > 0 ? ref.substring(dot + 1) : "";
            if (!refs.containsKey(coll)) {
                errors.add("measure '" + col + "': catalogReference '" + ref + "' does not name a collection; use collection.field, e.g. holdings.drawnUsd");
            } else if (!refs.get(coll).contains(name)) {
                errors.add("measure '" + col + "': catalogReference '" + ref + "' is not a field or derived metric of " + coll
                        + "; valid names: " + String.join(", ", refs.get(coll)));
            }
            measures.put(col, m);
        }
        for (JsonNode c : in.get("columns")) {
            if ("number".equals(c.path("type").asText()) && !measures.containsKey(c.path("name").asText()))
                errors.add("numeric column '" + c.path("name").asText() + "' needs a measures entry (definition + catalogReference)");
        }
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
