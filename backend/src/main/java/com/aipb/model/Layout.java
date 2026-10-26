package com.aipb.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dynamic UI definition. The React shell renders navigation, pages and widgets
 * purely from this document, so the UI can be reshaped at runtime.
 */
@Data
@NoArgsConstructor
@Document("layouts")
public class Layout {
    @Id
    private String id;          // "default" or a banker id
    private String owner;
    private int version;
    private Instant updatedAt;
    private List<Page> pages = new ArrayList<>();

    public Page page(String pageId) {
        return pages.stream().filter(p -> p.getId().equals(pageId)).findFirst().orElse(null);
    }

    @Data @NoArgsConstructor
    public static class Page {
        private String id;
        private String title;
        private String icon;
        private String description;
        private boolean system;      // system pages cannot be deleted
        private String createdBy;    // "system" | "banker"
        /** The page's entity: which client it shows (Client 360's picker), chosen in the top bar. Empty for a book-wide page. */
        private List<PageParam> params = new ArrayList<>();
        /** The page's lens beyond the entity: a period and filters its widgets follow. Null when the page has none. */
        private PageContext context;
        private List<Widget> widgets = new ArrayList<>();

        public PageParam param(String name) {
            return params == null ? null : params.stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
        }
    }

    /**
     * A page input whose choices come from a source collection: each document's {@code valueField} is the value
     * passed to widgets and {@code labelField} is what the picker shows.
     */
    @Data @NoArgsConstructor
    public static class PageParam {
        private String name;         // e.g. clientId - matched by name against widget spec params
        private String label;        // e.g. Client
        private String collection;   // e.g. clients
        private String valueField;   // e.g. clientId
        private String labelField;   // e.g. name
    }

    /**
     * What a page lets the banker choose for all its widgets at once, besides the client: a {@code period} (a choice of
     * windows, one of them the default) and {@code filters} (one per dimension, e.g. region). A widget follows the period
     * if its query asks to; filters apply to every widget whose figures can be filtered that way.
     */
    @Data @NoArgsConstructor
    public static class PageContext {
        private PeriodSlot period;
        private List<FilterSlot> filters = new ArrayList<>();
    }

    @Data @NoArgsConstructor
    public static class PeriodSlot {
        private String label = "Period";
        private String defaultOption;
        private List<PeriodOption> options = new ArrayList<>();

        public PeriodOption option(String id) {
            return options.stream().filter(o -> o.getId().equals(id)).findFirst().orElse(null);
        }
    }

    /** A named window, e.g. {id: "t12m", label: "Last 12 months", window: "trailing:month:12"} (a window key). */
    @Data @NoArgsConstructor
    public static class PeriodOption {
        private String id;
        private String label;
        private String window;
    }

    /** A filter on a conformed dimension (region, line of business, ...); its choices come from the semantic layer. */
    @Data @NoArgsConstructor
    public static class FilterSlot {
        private String dimension;
        private String label;
    }

    @Data @NoArgsConstructor
    public static class Widget {
        private String id;
        private String type;         // key in the React widget registry; "spec" = a library definition (config.definitionId)
        // spec widgets' config: definitionId; params (fixed entity, e.g. {clientId}); bindings ({period: optionId or
        // window key, filters: {dimension: "v1,v2"}}) - this placement's own context, over the page's
        private String title;
        private int span = 12;       // grid columns out of 12
        private Map<String, Object> config = new LinkedHashMap<>();
        private String createdBy;    // "system" | "banker"
        /** Set for a widget not approved yet: only this actor sees it on the page (see LayoutService.get(id, actorId)). */
        private String visibleTo;
    }
}
