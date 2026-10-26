package com.aipb.widget;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * A widget definition in the library (aipb.widgets). The spec is stored verbatim as JSON text - its aggregation
 * pipeline is full of $-prefixed keys that do not belong in a stored document's field names - and the fields worth
 * listing or filtering on are copied alongside it. Pages place a definition by id (see Layout.Widget config).
 */
@Data
@NoArgsConstructor
@Document("widgets")
public class WidgetDefinition {
    @Id
    private String id;
    private int version;
    private String title;
    private String description;
    private String primitive;
    private String collection;
    private List<String> params;
    private String author;       // "starter" | "workbench" | later "assistant"
    private String spec;         // the WidgetSpec JSON, as saved
    private String owner;        // actor id; "system" for starters
    private String scope;        // personal | global (null: global - saved before roles existed)
    private Review review;       // the request to publish a personal widget to the global library
    private Instant createdAt;
    private Instant updatedAt;

    public static final String PERSONAL = "personal";
    public static final String GLOBAL = "global";

    public boolean global() { return scope == null || GLOBAL.equals(scope); }

    /** Global definitions are visible to everyone; personal ones only to their owner. */
    public boolean visibleTo(String actorId) { return global() || (owner != null && owner.equals(actorId)); }

    public String reviewStatus() { return review == null || review.getStatus() == null ? "none" : review.getStatus(); }

    @Data
    @NoArgsConstructor
    public static class Review {
        private String status;       // none | pending | approved | rejected
        private String submittedBy;
        private Instant submittedAt;
        private String note;
    }
}
