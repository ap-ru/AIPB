package com.aipb.web;

import com.aipb.context.ContextResolver;
import com.aipb.model.Layout;
import com.aipb.seed.DataSeeder;
import com.aipb.service.LayoutService;
import com.aipb.service.WidgetManifest;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetPrimitives;
import com.aipb.widget.WidgetPlacement;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Dynamic UI (layouts and widgets) and platform status. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlatformController {

    private final LayoutService layouts;
    private final DataSeeder seeder;
    private final WidgetDataService widgetData;
    private final WidgetPrimitives primitives;
    private final WidgetPlacement placement;
    private final ContextResolver contexts;

    // ------------------------------------------------------------------ layouts
    @GetMapping("/layouts/{id}")
    public Layout layout(@PathVariable String id) { return layouts.get(id); }

    @PutMapping("/layouts/{id}")
    public Layout saveLayout(@PathVariable String id, @RequestBody Layout body) {
        body.setId(id);
        return layouts.save(body);
    }

    @PostMapping("/layouts/{id}/reset")
    public Layout reset(@PathVariable String id) { return layouts.reset(id); }

    @PostMapping("/layouts/{id}/pages")
    public Layout addPage(@PathVariable String id, @RequestBody Layout.Page page) {
        if (page.getCreatedBy() == null) page.setCreatedBy("banker");
        layouts.addPage(id, page);
        return layouts.get(id);
    }

    @DeleteMapping("/layouts/{id}/pages/{pageId}")
    public Layout deletePage(@PathVariable String id, @PathVariable String pageId, @RequestParam(defaultValue = "false") boolean force) {
        layouts.deletePage(id, pageId, force);
        return layouts.get(id);
    }

    @PostMapping("/layouts/{id}/pages/{pageId}/widgets")
    public Layout addWidget(@PathVariable String id, @PathVariable String pageId, @RequestBody Layout.Widget w,
                            @RequestParam(required = false) Integer position) {
        if (w.getCreatedBy() == null) w.setCreatedBy("banker");
        return placement.place(id, pageId, w, position);
    }

    @PatchMapping("/layouts/{id}/pages/{pageId}/widgets/{widgetId}")
    public Layout updateWidget(@PathVariable String id, @PathVariable String pageId, @PathVariable String widgetId, @RequestBody Layout.Widget patch) {
        layouts.updateWidget(id, pageId, widgetId, patch);
        return layouts.get(id);
    }

    @PostMapping("/layouts/{id}/pages/{pageId}/widgets/{widgetId}/move")
    public Layout move(@PathVariable String id, @PathVariable String pageId, @PathVariable String widgetId, @RequestParam int delta) {
        return layouts.moveWidget(id, pageId, widgetId, delta);
    }

    @DeleteMapping("/layouts/{id}/pages/{pageId}/widgets/{widgetId}")
    public Layout removeWidget(@PathVariable String id, @PathVariable String pageId, @PathVariable String widgetId) {
        layouts.removeWidget(id, pageId, widgetId);
        return layouts.get(id);
    }

    /** The choices for a page parameter (e.g. the clients a Client 360 page can show): [{value, label}]. */
    @GetMapping("/layouts/{id}/pages/{pageId}/params/{name}/options")
    public List<Map<String, String>> paramOptions(@PathVariable String id, @PathVariable String pageId, @PathVariable String name) {
        Layout.Page page = layouts.get(id).page(pageId);
        if (page == null) throw new NoSuchElementException("page " + pageId);
        Layout.PageParam p = page.param(name);
        if (p == null) throw new NoSuchElementException("page " + pageId + " has no parameter " + name);
        return widgetData.options(p);
    }

    /** The choices for one of a page's filters (e.g. its regions): the dimension's values, or the distinct values in the data. */
    @GetMapping("/layouts/{id}/pages/{pageId}/context/filters/{dimension}/options")
    public List<String> filterOptions(@PathVariable String id, @PathVariable String pageId, @PathVariable String dimension) {
        Layout.Page page = layouts.get(id).page(pageId);
        if (page == null) throw new NoSuchElementException("page " + pageId);
        if (page.getContext() == null || page.getContext().getFilters().stream().noneMatch(f -> f.getDimension().equals(dimension)))
            throw new NoSuchElementException("page " + pageId + " has no filter on " + dimension);
        return contexts.filterOptions(dimension);
    }

    /** Widget types, their config schemas, and the widget spec contract (JSON Schema + design-system primitives). */
    @GetMapping("/widgets/manifest")
    public Map<String, Object> manifest() {
        Map<String, Object> m = new LinkedHashMap<>(WidgetManifest.describe());
        m.put("specSchema", primitives.schemaJson());
        m.put("primitives", primitives.catalog());
        return m;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("ok", true, "layoutVersion", layouts.get("default").getVersion(), "widgets", WidgetManifest.TYPES.size());
    }

    @PostMapping("/admin/reseed")
    public Map<String, Object> reseed() {
        seeder.wipe();
        seeder.seedAll();
        return Map.of("status", "reseeded");
    }
}
