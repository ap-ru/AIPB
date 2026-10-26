package com.aipb.widget;

import com.aipb.identity.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The Skills window and the approval queue. GET /api/skills lists the widgets for the caller (the
 * actor from the X-AIPB-Role header); the submission endpoints show one submission (its preview, history and - for a change - the
 * current version beside it) and take a Reviewer's decision (approve, feedback & revise, reject).
 */
@RestController
@RequiredArgsConstructor
public class SkillsController {

    private final WidgetWorkflow workflow;
    private final WidgetLibrary library;

    public record DecisionRequest(String comment) {}

    @GetMapping("/api/skills")
    public Map<String, Object> skills(Actor actor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", actor.role().id());
        m.put("attention", workflow.attention(actor));
        m.put("sections", workflow.skills(actor));
        return m;
    }

    /** One submission: what it proposes, a live preview, its history, and the version it would replace. */
    @GetMapping("/api/submissions/{id}")
    public Map<String, Object> get(@PathVariable String id, @RequestParam Map<String, String> params) {
        WidgetSubmission s = workflow.get(id);
        JsonNode spec = workflow.spec(s);
        Map<String, Object> sample = new LinkedHashMap<>(s.getParams());
        params.forEach(sample::put);
        Map<String, Object> m = workflow.item(s);
        m.put("spec", spec);
        m.put("history", s.getHistory());
        m.putAll(WidgetController.preview(spec, workflow.preview(spec, s.getLayoutId(), s.getPageId(), strings(sample))));
        if (s.getBaseVersion() != null) {
            library.find(s.getWidgetId()).ifPresent(d -> {
                JsonNode cur = library.spec(d);
                Map<String, Object> current = WidgetController.summary(d);
                current.putAll(WidgetController.preview(cur, workflow.preview(cur, s.getLayoutId(), s.getPageId(), strings(sample))));
                m.put("current", current);
            });
        }
        return m;
    }

    @PostMapping("/api/submissions/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) DecisionRequest req,
                                       Actor actor) {
        return workflow.item(workflow.approve(id, actor, comment(req)));
    }

    @PostMapping("/api/submissions/{id}/send-back")
    public Map<String, Object> sendBack(@PathVariable String id, @RequestBody(required = false) DecisionRequest req,
                                        Actor actor) {
        return workflow.item(workflow.sendBack(id, actor, comment(req)));
    }

    @PostMapping("/api/submissions/{id}/reject")
    public Map<String, Object> reject(@PathVariable String id, @RequestBody(required = false) DecisionRequest req,
                                      Actor actor) {
        return workflow.item(workflow.reject(id, actor, comment(req)));
    }

    private static String comment(DecisionRequest req) { return req == null ? null : req.comment(); }

    private static Map<String, String> strings(Map<String, Object> in) {
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, v) -> { if (v != null) out.put(k, String.valueOf(v)); });
        return out;
    }
}
