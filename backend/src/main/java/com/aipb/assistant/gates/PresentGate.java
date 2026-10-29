package com.aipb.assistant.gates;

import com.aipb.assistant.AssistantTools;
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
 * Assembles the full widget spec from the accepted data (BUILD) and the display design (PRESENT), then checks it with
 * the widget validator against the target page: roles fit the primitive and the columns, row counts suit it, units
 * and formats agree, and the page can supply every parameter.
 */
@Component
@RequiredArgsConstructor
public class PresentGate {

    private static final Set<String> ROLE_KEYS = Set.of("value", "compare", "secondary", "x", "y", "orientation", "stacked",
            "columns", "title", "subtitle", "badge", "meta", "reference", "emphasis", "polarity", "headline", "warnAt");

    private final WidgetSpecValidator validator;
    private final ObjectMapper mapper;

    /** {@code spec} and {@code report} are set when the assembled spec passed. */
    public record Result(boolean accepted, List<String> errors, ObjectNode spec, ValidationReport report) {}

    public Result check(JsonNode design, JsonNode artifact, AssistantTools.Context ctx) {
        List<String> errors = new ArrayList<>();
        return validated(assemble(design, artifact, errors), errors, ctx);
    }

    /**
     * An insight: the spec assembled from the data and the narrate design (statements and figures for the narrative
     * primitive), checked by the same validator - which also renders the statements on the sample.
     */
    public Result checkNarrative(JsonNode design, JsonNode artifact, AssistantTools.Context ctx) {
        List<String> errors = new ArrayList<>();
        ObjectNode spec = assemble(design, artifact, errors);
        ObjectNode view = spec.putObject("view");
        view.put("primitive", "narrative");
        ArrayNode statements = view.putArray("statements");
        design.path("statements").forEach(st -> {
            ObjectNode o = statements.addObject();
            JsonNode when = st.path("when");
            if (!when.path("column").asText("").isBlank()) {
                ObjectNode w = o.putObject("when");
                w.put("column", when.path("column").asText());
                w.put("op", when.path("op").asText());
                w.set("value", conditionValue(when.path("value").asText("")));
            }
            o.put("text", st.path("text").asText());
            o.put("tone", st.path("tone").asText("neutral"));
        });
        view.set("figures", design.path("figures").isArray() ? design.get("figures") : mapper.createArrayNode());
        return validated(spec, errors, ctx);
    }

    /** A condition's value, written as text in the design: a number when it reads as one, true/false, otherwise the text. */
    private JsonNode conditionValue(String v) {
        String t = v.trim();
        if (t.matches("-?[0-9]+(\\.[0-9]+)?")) return mapper.getNodeFactory().numberNode(Double.parseDouble(t));
        if (t.equals("true") || t.equals("false")) return mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(t));
        return mapper.getNodeFactory().textNode(t);
    }

    private Result validated(ObjectNode spec, List<String> errors, AssistantTools.Context ctx) {
        if (!errors.isEmpty()) return new Result(false, errors, null, null);
        ValidationReport report = validator.validate(spec, ctx.layoutId(), ctx.pageId(), ctx.sampleParams());
        report.errors().forEach(e -> errors.add(e.code() + " (" + e.path() + "): " + e.message()));
        if (!errors.isEmpty()) return new Result(false, errors, spec, report);
        return new Result(true, List.of(), spec, report);
    }

    ObjectNode assemble(JsonNode design, JsonNode artifact, List<String> errors) {
        ObjectNode spec = mapper.createObjectNode();
        String title = design.path("title").asText("Widget");
        spec.put("id", slug(title));
        spec.put("specVersion", 2);
        spec.put("version", 1);
        spec.put("title", title.length() > 80 ? title.substring(0, 80) : title);
        String desc = design.path("description").asText(title);
        spec.put("description", desc.length() > 400 ? desc.substring(0, 400) : desc);
        ObjectNode data = spec.putObject("data");
        ArrayNode columns = artifact.path("columns").deepCopy();
        applyOverrides(columns, design.path("columnOverrides"), errors);
        if (artifact.has("query")) {
            // a semantic query (a library widget being changed): its columns come from the semantic layer, so only
            // labels and badge tones can be overridden
            data.set("query", artifact.get("query"));
            data.set("columns", queryOverrides(artifact, columns));
        } else {
            spec.set("params", artifact.path("params"));
            ObjectNode custom = data.putObject("custom");
            custom.set("cypher", artifact.path("cypher"));
            custom.set("columns", columns);
        }

        ObjectNode view = spec.putObject("view");
        view.put("primitive", design.path("primitive").asText());
        design.path("roles").fields().forEachRemaining(e -> {
            if (ROLE_KEYS.contains(e.getKey()) && !unused(e.getValue())) view.set(e.getKey(), e.getValue());
        });
        int span = design.path("span").asInt(6);
        spec.putObject("layout").put("span", Math.max(3, Math.min(12, span)));
        return spec;
    }

    /**
     * The design schema has no optional or nullable fields (strict schemas with many of them are too complex for the
     * API to compile), so a role the primitive does not use arrives as its unused value: "" for a column, [] for a
     * list, "default" for orientation and intents, false for stacked, 0 for warnAt. Null is accepted too.
     */
    static boolean unused(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return true;
        if (v.isTextual()) return v.asText().isBlank() || "default".equals(v.asText());
        if (v.isArray()) return v.isEmpty();
        if (v.isBoolean()) return !v.asBoolean();
        if (v.isNumber()) return v.asDouble() == 0;
        return false;
    }

    /** A query's column overrides: the ones it had, with the design's labels and tones on top. */
    private ArrayNode queryOverrides(JsonNode artifact, ArrayNode columns) {
        java.util.Map<String, ObjectNode> out = new java.util.LinkedHashMap<>();
        artifact.path("overrides").forEach(o -> out.put(o.path("name").asText(), (ObjectNode) o.deepCopy()));
        java.util.Map<String, JsonNode> before = new java.util.HashMap<>();
        artifact.path("columns").forEach(c -> before.put(c.path("name").asText(), c));
        for (JsonNode c : columns) {
            String name = c.path("name").asText();
            JsonNode was = before.getOrDefault(name, mapper.createObjectNode());
            boolean relabelled = !c.path("label").asText().equals(was.path("label").asText());
            boolean retoned = c.has("tones") && !c.get("tones").equals(was.path("tones"));
            if (!relabelled && !retoned) continue;
            ObjectNode o = out.computeIfAbsent(name, k -> mapper.createObjectNode().put("name", k));
            if (relabelled) o.put("label", c.path("label").asText());
            if (retoned) o.set("tones", c.get("tones"));
        }
        ArrayNode arr = mapper.createArrayNode();
        out.values().forEach(arr::add);
        return arr;
    }

    private static void applyOverrides(ArrayNode columns, JsonNode overrides, List<String> errors) {
        if (!overrides.isArray()) return;
        for (JsonNode o : overrides) {
            String name = o.path("name").asText();
            ObjectNode col = null;
            for (JsonNode c : columns) if (name.equals(c.path("name").asText())) col = (ObjectNode) c;
            if (col == null) { errors.add("columnOverrides names '" + name + "', which is not a column of the data"); continue; }
            if (o.hasNonNull("label") && !o.get("label").asText().isBlank()) col.put("label", o.get("label").asText());
            JsonNode tones = o.path("badgeTones");
            if (tones.isArray() && tones.size() > 0) {
                if (!"string".equals(col.path("type").asText())) {
                    errors.add("badgeTones only apply to text columns; '" + name + "' has type " + col.path("type").asText());
                    continue;
                }
                ObjectNode map = col.putObject("tones");
                tones.forEach(t -> map.put(t.path("value").asText(), t.path("tone").asText()));
                col.put("unit", "none");
                col.put("format", "badge");
            }
        }
    }

    /** A spec id from the title: lower-case words joined by hyphens, 3-64 characters. */
    public static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > 56) s = s.substring(0, 56).replaceAll("-+$", "");
        if (s.length() < 3) s = "widget-" + s;
        return s.replaceAll("^-+", "");
    }
}
