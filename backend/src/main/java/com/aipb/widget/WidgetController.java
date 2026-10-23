package com.aipb.widget;

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

    @GetMapping("/library")
    public List<Map<String, Object>> list() {
        return library.list().stream().map(WidgetController::summary).toList();
    }

    @GetMapping("/library/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        WidgetDefinition d = library.get(id);
        Map<String, Object> m = summary(d);
        m.put("spec", library.spec(d));
        return m;
    }

    /** A new widget from the workbench: a builder's waits for approval; an approver's is approved as it is saved. */
    @PostMapping("/library")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody SpecRequest req, @RequestHeader(value = Role.HEADER, required = false) String role) {
        Optional<WidgetSubmission> open = resubmittable(required(req).path("id").asText());
        if (open.isPresent()) return submitted(workflow.resubmit(open.get().getId(), req.spec(), Role.of(role)));
        return submitted(workflow.submit(new WidgetWorkflow.Submit(required(req), "workbench", null, req.layoutId(), req.pageId(),
                req.params(), null), Role.of(role)));
    }

    /** A workbench widget waiting for approval (or sent back) is revised by submitting its id again. */
    private Optional<WidgetSubmission> resubmittable(String id) {
        return workflow.openFor(id).filter(s -> "workbench".equals(s.getOrigin()));
    }

    /** A change to a library widget from the workbench, submitted as its next version. */
    @PutMapping("/library/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody SpecRequest req,
                                      @RequestHeader(value = Role.HEADER, required = false) String role) {
        if (!id.equals(required(req).path("id").asText()))
            throw new IllegalArgumentException("spec id '" + req.spec().path("id").asText() + "' does not match '" + id + "'");
        Optional<WidgetSubmission> open = resubmittable(id);
        if (open.isPresent()) return submitted(workflow.resubmit(open.get().getId(), req.spec(), Role.of(role)));
        return submitted(workflow.submit(new WidgetWorkflow.Submit(req.spec(), "workbench", null, req.layoutId(), req.pageId(),
                req.params(), id), Role.of(role)));
    }

    /** A live preview of a library widget outside a page: request values (e.g. clientId) over its sample values. */
    @GetMapping("/library/{id}/preview")
    public Map<String, Object> preview(@PathVariable String id, @RequestParam Map<String, String> params) {
        WidgetDefinition d = library.get(id);
        return preview(library.spec(d), workflow.preview(library.spec(d), null, null, params));
    }

    /** Every version of a widget, newest first (approvers only). */
    @GetMapping("/library/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id, @RequestHeader(value = Role.HEADER, required = false) String role) {
        Role.of(role).requireApprover("see a widget's version history");
        int current = library.get(id).getVersion();
        return library.history(id).stream().map(v -> version(v, current)).toList();
    }

    /** One version, with a live preview (approvers only). */
    @GetMapping("/library/{id}/versions/{version}")
    public Map<String, Object> version(@PathVariable String id, @PathVariable int version, @RequestParam Map<String, String> params,
                                       @RequestHeader(value = Role.HEADER, required = false) String role) {
        Role.of(role).requireApprover("see a widget's version history");
        WidgetVersion v = library.version(id, version);
        JsonNode spec = library.spec(v);
        Map<String, Object> m = version(v, library.get(id).getVersion());
        m.put("spec", spec);
        m.putAll(preview(spec, workflow.preview(spec, null, null, params)));
        return m;
    }

    /** Makes an older version current again, as the next version (approvers only). */
    @PostMapping("/library/{id}/versions/{version}/restore")
    public Map<String, Object> restore(@PathVariable String id, @PathVariable int version,
                                       @RequestHeader(value = Role.HEADER, required = false) String role) {
        return summary(workflow.restore(id, version, Role.of(role)));
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
        m.put("title", d.getTitle());
        m.put("description", d.getDescription());
        m.put("primitive", d.getPrimitive());
        m.put("collection", d.getCollection());
        m.put("params", d.getParams());
        m.put("author", d.getAuthor());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
