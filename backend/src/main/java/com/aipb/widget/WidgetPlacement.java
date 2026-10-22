package com.aipb.widget;

import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts widgets on pages. A library (spec) widget must get every parameter it needs - a fixed value in config.params,
 * a parameter of the page, or a built-in (asOfDate) - or placement is refused. Used by the layout API and by the
 * assistant when a banker approves a proposal.
 */
@Service
@RequiredArgsConstructor
public class WidgetPlacement {

    private final LayoutService layouts;
    private final WidgetDataService widgetData;
    private final WidgetPrimitives primitives;

    /** Places any widget (spec widgets are checked first) and returns the updated layout. */
    public Layout place(String layoutId, String pageId, Layout.Widget w, Integer position) {
        if ("spec".equals(w.getType())) checkSpec(layoutId, pageId, w);
        layouts.addWidget(layoutId, pageId, w, position);
        return layouts.get(layoutId);
    }

    /** Places a library definition, titled and sized from the definition. */
    public Layout placeDefinition(String layoutId, String pageId, String definitionId, String createdBy) {
        Layout.Widget w = new Layout.Widget();
        w.setType("spec");
        w.setSpan(0);
        w.setCreatedBy(createdBy);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("definitionId", definitionId);
        w.setConfig(cfg);
        return place(layoutId, pageId, w, null);
    }

    /** Fills in the title (and width, if none was sent) from the definition. */
    void checkSpec(String layoutId, String pageId, Layout.Widget w) {
        Object defId = w.getConfig() == null ? null : w.getConfig().get("definitionId");
        if (defId == null) throw new IllegalArgumentException("a spec widget needs config.definitionId");
        WidgetSpec spec = widgetData.spec(String.valueOf(defId));
        Layout.Page page = layouts.get(layoutId).page(pageId);
        Map<?, ?> fixed = w.getConfig().get("params") instanceof Map<?, ?> m ? m : Map.of();
        List<String> missing = widgetData.requiredParams(spec).stream()
                .filter(n -> !primitives.builtinParams().contains(n) && !fixed.containsKey(n) && (page == null || page.param(n) == null))
                .toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("widget '" + spec.id() + "' needs " + String.join(", ", missing) + ", which page '"
                    + (page == null ? pageId : page.getTitle()) + "' does not supply; place it on a page that does, or set config.params");
        if (w.getTitle() == null || w.getTitle().isBlank()) w.setTitle(spec.title());
        if (w.getSpan() <= 0) w.setSpan(spec.layout() != null && spec.layout().span() != null ? spec.layout().span()
                : primitives.primitive(spec.view().primitive()).path("defaultSpan").asInt(12));
    }
}
