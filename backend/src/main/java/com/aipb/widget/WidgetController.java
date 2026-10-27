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

    @PostMapping("/library")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody SpecRequest req, Actor actor) {
        actor.requireAuthor();
        WidgetLibrary.Saved s = library.create(required(req), "workbench", actor.id(), WidgetDefinition.PERSONAL,
                req.layoutId(), req.pageId(), req.params());
        return saved(s);
    }

    @PutMapping("/library/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody SpecRequest req, Actor actor) {
        actor.requireAuthor();
        WidgetLibrary.Saved s = library.update(id, required(req), "workbench", actor.id(), req.layoutId(), req.pageId(), req.params());
        return saved(s);
    }

    /** Asks for one of the actor's personal widgets to be published to the global library. */
    @PostMapping("/library/{id}/submit")
    public Map<String, Object> submit(@PathVariable String id, @RequestBody(required = false) SubmitRequest req, Actor actor) {
        actor.requireAuthor();
        return summary(library.submitForReview(id, actor.id(), req == null ? null : req.note()));
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

    private Map<String, Object> saved(WidgetLibrary.Saved s) {
        Map<String, Object> m = summary(s.definition());
        m.put("report", s.report());
        return m;
    }

    static Map<String, Object> summary(WidgetDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("version", d.getVersion());
        m.put("title", d.getTitle());
        m.put("description", d.getDescription());
        m.put("kind", d.kind());
        m.put("templateId", d.getTemplateId());
        m.put("primitive", d.getPrimitive());
        m.put("collection", d.getCollection());
        m.put("params", d.getParams());
        m.put("author", d.getAuthor());
        m.put("owner", d.getOwner());
        m.put("scope", d.global() ? WidgetDefinition.GLOBAL : WidgetDefinition.PERSONAL);
        m.put("review", d.reviewStatus());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }
}
