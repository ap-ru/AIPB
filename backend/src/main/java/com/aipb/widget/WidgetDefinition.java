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
 * <p>
 * A definition is personal or global. A personal one is a Builder's widget that is not approved yet: only its owner
 * sees it, and only on their own view of a page. A Reviewer's approval makes it global - live for everyone - as v1;
 * every approved version is kept in aipb.widget_versions, and changes to a global widget wait in
 * aipb.widget_submissions until approved.
 */
@Data
@NoArgsConstructor
@Document("widgets")
public class WidgetDefinition {
    public static final String STOCK = "stock";  // a starter widget from the repo
    public static final String BUILT = "built";  // made in the app
    public static final String PERSONAL = "personal";
    public static final String GLOBAL = "global";

    @Id
    private String id;
    private int version;         // the current approved version (a personal widget's draft is 1 until approved)
    private String origin;       // stock | built
    private String title;
    private String description;
    private String primitive;
    private String collection;
    private List<String> params;
    private String author;       // "starter" | "workbench" | "assistant"
    private String spec;         // the WidgetSpec JSON, as saved
    private String owner;        // actor id (e.g. demo-builder); "system" for stock widgets
    private String scope;        // personal | global (null: global - saved before roles existed)
    private Review review;       // the request to publish a personal widget to the global library
    private String kind;         // the skill kind: widget (null: widget - saved before skills existed)
    private String templateId;   // the Skills Studio template it was built from, if any
    private Integer templateVersion;
    private Instant createdAt;
    private Instant updatedAt;

    public static final String WIDGET = "widget";

    /** Where a skill came from: its kind and the template that guided the assistant run (null for none). */
    public record SkillOrigin(String kind, String templateId, Integer templateVersion) {}

    public String kind() { return kind == null ? WIDGET : kind; }

    public SkillOrigin skillOrigin() { return new SkillOrigin(kind, templateId, templateVersion); }

    public void setSkillOrigin(SkillOrigin o) {
        kind = o == null ? null : o.kind();
        templateId = o == null ? null : o.templateId();
        templateVersion = o == null ? null : o.templateVersion();
    }

    public boolean global() { return scope == null || GLOBAL.equals(scope); }

    /** Global definitions are visible to everyone; personal ones only to their owner. */
    public boolean visibleTo(String actorId) { return global() || (owner != null && owner.equals(actorId)); }

    /** none | pending | changes_requested (feedback & revise) | approved | rejected */
    public String reviewStatus() { return review == null || review.getStatus() == null ? "none" : review.getStatus(); }

    @Data
    @NoArgsConstructor
    public static class Review {
        private String status;       // pending | changes_requested | approved | rejected
        private String submittedBy;
        private Instant submittedAt;
        private String note;
        private String submissionId; // the review in aipb.widget_submissions
    }
}
