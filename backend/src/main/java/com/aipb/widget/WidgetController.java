package com.aipb.widget;

import com.aipb.identity.Actor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The widget library and its data. The browser only ever runs stored, validated definitions by id; the one place a
 * spec is sent in is /validate (and the saves, which validate first) - the authoring path.
 */
@RestController
@RequestMapping("/api/widgets")
@RequiredArgsConstructor
public class WidgetController {

    private final WidgetLibrary library;
    private final WidgetWorkflow workflow;
    private final WidgetSpecValidator validator;
    private final WidgetDataService data;

    /** A spec plus where it is meant to go: the page decides which parameters exist; params are sample values. */
    public record SpecRequest(JsonNode spec, String layoutId, String pageId, Map<String, String> params) {}

    public record SubmitRequest(String note) {}

    /** The global library plus the actor's personal widgets. */
    @GetMapping("/library")
    public List<Map<String, Object>> list(Actor actor) {
        return library.list(actor.id()).stream().map(WidgetController::summary).toList();
    }

    @GetMapping("/library/{id}")
    public Map<String, Object> get(@PathVariable String id, Actor actor) {
        WidgetDefinition d = library.get(id, actor.id());
        Map<String, Object> m = summary(d);
        m.put("spec", library.spec(d));
        return m;
    }

    /**
     * A new widget from the workbench. A Builder's goes to their personal library (they can place it; only they see it
     * until it is approved; Submit for review shares it). A Reviewer's is approved as it is saved.
     */
    @PostMapping("/library")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody SpecRequest req, Actor actor) {
        actor.requireAuthor();
        if (actor.role().canReview())
            return submitted(workflow.submit(new WidgetWorkflow.Submit(required(req), "workbench", null, req.layoutId(), req.pageId(),
                    req.params(), null), actor));
        WidgetLibrary.Saved s = library.create(required(req), "workbench", actor.id(), WidgetDefinition.PERSONAL,
                req.layoutId(), req.pageId(), req.params());
        return saved(s);
    }

    /**
     * A new version of a definition: the actor's own personal widget is revised in place (share it again for review);
     * a change to a global widget is submitted for approval as its next version.
     */
    @PutMapping("/library/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody SpecRequest req, Actor actor) {
        actor.requireAuthor();
        if (!id.equals(required(req).path("id").asText()))
            throw new IllegalArgumentException("spec id '" + req.spec().path("id").asText() + "' does not match '" + id + "'");
        WidgetDefinition current = library.get(id, actor.id());
        if (!current.global())
            return saved(library.update(id, req.spec(), "workbench", actor.id(), req.layoutId(), req.pageId(), req.params()));
        Optional<WidgetSubmission> open = workflow.openFor(id).filter(s -> "workbench".equals(s.getOrigin()));
        if (open.isPresent()) return submitted(workflow.resubmit(open.get().getId(), req.spec(), actor));
        return submitted(workflow.submit(new WidgetWorkflow.Submit(req.spec(), "workbench", null, req.layoutId(), req.pageId(),
                req.params(), id), actor));
    }

    /** Asks for one of the actor's personal widgets to be published to the global library: it goes to the Reviewers. */
    @PostMapping("/library/{id}/submit")
    public Map<String, Object> submit(@PathVariable String id, @RequestBody(required = false) SubmitRequest req, Actor actor) {
        actor.requireAuthor();
        WidgetDefinition d = library.get(id, actor.id());
        JsonNode spec = library.spec(d);
        workflow.share(id, actor, req == null ? null : req.note(), d.getAuthor() == null ? "workbench" : d.getAuthor(), null,
                "default", null, library.sampleParams(spec));
        return summary(library.get(id));
    }

    /** A live preview of a library widget outside a page: request values (e.g. clientId) over its sample values. */
    @GetMapping("/library/{id}/preview")
    public Map<String, Object> preview(@PathVariable String id, @RequestParam Map<String, String> params, Actor actor) {
        WidgetDefinition d = library.get(id, actor.id());
        return preview(library.spec(d), workflow.preview(library.spec(d), null, null, params));
    }

    /** Every version of a widget, newest first (Reviewers only). */
    @GetMapping("/library/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id, Actor actor) {
        actor.requireReviewer("see a widget's version history");
        int current = library.get(id).getVersion();
        return library.history(id).stream().map(v -> version(v, current)).toList();
    }

    /** One version, with a live preview (Reviewers only). */
    @GetMapping("/library/{id}/versions/{version}")
    public Map<String, Object> version(@PathVariable String id, @PathVariable int version, @RequestParam Map<String, String> params,
                                       Actor actor) {
        actor.requireReviewer("see a widget's version history");
        WidgetVersion v = library.version(id, version);
        JsonNode spec = library.spec(v);
        Map<String, Object> m = version(v, library.get(id).getVersion());
        m.put("spec", spec);
        m.putAll(preview(spec, workflow.preview(spec, null, null, params)));
        return m;
    }

    /** Makes an older version current again, as the next version (Reviewers only). */
    @PostMapping("/library/{id}/versions/{version}/restore")
    public Map<String, Object> restore(@PathVariable String id, @PathVariable int version,
                                       Actor actor) {
        return summary(workflow.restore(id, version, actor));
    }

    /**
     * Runs a stored definition outside a page: its rows plus the view that displays them. Request values: clientId,
     * period (a window key such as trailing:month:12) and filter.<dimension> (e.g. filter.region=EMEA,APAC).
     */
    @GetMapping("/library/{id}/data")
    public Map<String, Object> data(@PathVariable String id, @RequestParam Map<String, String> params) {
        return body(id, data.data(id, params));
    }

    /** Runs a widget placed on a page, in the page's context: the URL's clientId, period and filters, then the placement's bindings. */
    @GetMapping("/placed/{layoutId}/{pageId}/{widgetId}/data")
    public Map<String, Object> placed(@PathVariable String layoutId, @PathVariable String pageId, @PathVariable String widgetId,
                                      @RequestParam Map<String, String> params) {
        WidgetDataService.Placed p = data.placed(layoutId, pageId, widgetId, params);
        return body(p.definitionId(), p.data());
    }

    /** Rows, the view that displays them, and the context they were computed in (whose figures, period, filters). */
    private Map<String, Object> body(String id, WidgetDataService.DataResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definitionId", id);
        WidgetSpec spec = data.spec(id);
        m.put("view", spec.view());
        m.put("description", spec.description());
        m.put("columns", r.columns());
        m.put("rows", r.rows());
        if (WidgetSpecValidator.NARRATIVE.equals(spec.view().primitive()))
            m.put("narrative", NarrativeRenderer.render(spec.view(), r.columns(), r.rows()));
        m.put("count", r.count());
        m.put("total", r.total());
        m.put("truncated", r.truncated());
        m.put("asOfDate", r.asOfDate());
        m.put("params", r.params());
        if (!r.notes().isEmpty()) m.put("notes", r.notes());
        if (r.context() != null) m.put("context", r.context());
        return m;
    }

    /** Validates a spec without saving it: errors, warnings and a live preview. */
    @PostMapping("/validate")
    public ValidationReport validate(@RequestBody SpecRequest req) {
        return validator.validate(required(req), req.layoutId(), req.pageId(), req.params());
    }

    private static JsonNode required(SpecRequest req) {
        if (req == null || req.spec() == null || !req.spec().isObject()) throw new IllegalArgumentException("body must be {\"spec\": {...}}");
        return req.spec();
    }

    /** A personal save: the definition and its validation report. */
    private Map<String, Object> saved(WidgetLibrary.Saved s) {
        Map<String, Object> m = summary(s.definition());
        m.put("status", s.definition().global() ? "approved" : "personal");
        m.put("report", s.report());
        return m;
    }

    /** A workbench save: the submission, and - when it was approved at once - the widget it became. */
    private Map<String, Object> submitted(WidgetSubmission s) {
        Map<String, Object> m = workflow.item(s);
        m.put("id", s.getWidgetId());
        m.put("submissionId", s.getId());
        return m;
    }

    /** The view to draw a spec with, and the validator's run of it: rows, errors and warnings. */
    static Map<String, Object> preview(JsonNode spec, ValidationReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("view", spec.path("view"));
        m.put("ok", r.ok());
        m.put("errors", r.errors());
        m.put("warnings", r.warnings());
        m.put("sampleParams", r.sampleParams());
        m.put("preview", r.preview());
        return m;
    }

    private static Map<String, Object> version(WidgetVersion v, int current) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.getVersion());
        m.put("current", v.getVersion() == current);
        m.put("title", v.getTitle());
        m.put("description", v.getDescription());
        m.put("primitive", v.getPrimitive());
        m.put("source", v.getSource());
        m.put("restoredFrom", v.getRestoredFrom());
        m.put("submissionId", v.getSubmissionId());
        m.put("approvedByRole", v.getApprovedByRole());
        m.put("createdAt", v.getCreatedAt());
        return m;
    }

    static Map<String, Object> summary(WidgetDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("version", d.getVersion());
        m.put("origin", d.getOrigin());
        m.put("owner", d.getOwner());
        m.put("scope", d.global() ? WidgetDefinition.GLOBAL : WidgetDefinition.PERSONAL);
        m.put("review", d.reviewStatus());
        m.put("title", d.getTitle());
        m.put("description", d.getDescription());
        m.put("kind", d.kind());
        m.put("templateId", d.getTemplateId());
        m.put("primitive", d.getPrimitive());
        m.put("collection", d.getCollection());
        m.put("params", d.getParams());
        m.put("author", d.getAuthor());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
