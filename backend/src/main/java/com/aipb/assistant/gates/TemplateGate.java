package com.aipb.assistant.gates;

import com.aipb.skills.SkillTemplate;
import com.aipb.widget.WidgetDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Holds a run to its template's constraints, at each stage's existing gate: a breach is returned to the model as a
 * correction, like any other gate error. A template without constraints (Custom widget) never adds an error. Also
 * applies the template's brief defaults where Intake left a field open.
 */
@Component
public class TemplateGate {

    /** A proceed brief: the template's scope, and a display preference it can draw. */
    public List<String> intake(JsonNode brief, SkillTemplate t) {
        List<String> errors = new ArrayList<>();
        SkillTemplate.Constraints c = t.constraints();
        String scope = brief.path("scope").asText();
        if (c.scope() != null && !c.scope().equals(scope))
            errors.add("the " + t.title() + " template builds " + c.scope() + "-scope skills only, but the brief's scope is " + scope
                    + "; use " + c.scope() + " scope, or decline if the banker needs something else");
        String display = brief.path("display").asText("any");
        if (!c.primitives().isEmpty() && !"any".equals(display) && !c.primitives().contains(display))
            errors.add("the " + t.title() + " template draws " + String.join(" / ", c.primitives()) + " only; set display to one of these or any");
        return errors;
    }

    /** A submit_data submission, before its live run: it reads (at least) one of the template's labels. */
    public List<String> build(JsonNode submission, SkillTemplate t) {
        List<String> allowed = t.constraints().labels();
        List<String> used = WidgetDataService.labels(submission.path("cypher").asText());
        if (allowed.isEmpty() || used.stream().anyMatch(allowed::contains)) return List.of();
        return List.of("the " + t.title() + " template reads " + String.join(", ", allowed) + "; match one of these"
                + (used.isEmpty() ? "" : ", not only " + String.join(", ", used)));
    }

    /** An accepted data artifact: the row count of its sample run. */
    public List<String> rows(JsonNode artifact, SkillTemplate t) {
        Integer max = t.constraints().maxRows();
        int count = artifact.path("preview").path("count").asInt();
        if (max == null || count <= max) return List.of();
        return List.of("the sample returned " + count + " rows; the " + t.title() + " template allows at most " + max + " - aggregate or limit the result");
    }

    /** A Present design: an allowed primitive. */
    public List<String> present(JsonNode design, SkillTemplate t) {
        List<String> allowed = t.constraints().primitives();
        String p = design.path("primitive").asText();
        if (allowed.isEmpty() || allowed.contains(p)) return List.of();
        return List.of("the " + t.title() + " template draws " + String.join(" / ", allowed) + " only, not " + p);
    }

    /** The brief with the template's defaults filled in where Intake left a field open ("any", blank or null). */
    public JsonNode withDefaults(JsonNode brief, SkillTemplate t) {
        Map<String, Object> defaults = t.defaults().brief();
        if (defaults.isEmpty() || !brief.isObject()) return brief;
        ObjectNode out = ((ObjectNode) brief).deepCopy();
        defaults.forEach((k, v) -> {
            JsonNode cur = out.path(k);
            boolean open = cur.isMissingNode() || cur.isNull() || (cur.isTextual() && (cur.asText().isBlank() || "any".equals(cur.asText())));
            if (!open) return;
            if (v instanceof Number n) out.put(k, n.intValue());
            else out.put(k, String.valueOf(v));
            if (out.path("assumptions").isArray()) out.withArray("assumptions").add(k + ": " + v + " (the " + t.title() + " template's default)");
        });
        return out;
    }
}
