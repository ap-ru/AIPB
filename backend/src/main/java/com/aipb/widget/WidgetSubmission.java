package com.aipb.widget;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A request to add a widget to the library or change one (aipb.widget_submissions). It waits for a Reviewer, who
 * approves it (it becomes the widget's next version), gives feedback to revise (the Builder revises and resubmits the
 * same submission) or rejects it (final). Nothing in it reaches a page until it is approved.
 */
@Data
@NoArgsConstructor
@Document("widget_submissions")
public class WidgetSubmission {
    public static final String PENDING = "pending";
    public static final String CHANGES_REQUESTED = "changes_requested";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";
    /** Open submissions: still being decided or revised. A widget has at most one at a time. */
    public static final Set<String> OPEN = Set.of(PENDING, CHANGES_REQUESTED);

    @Id
    private String id;
    private String widgetId;
    private Integer baseVersion;     // the version being changed; null for a new widget
    private String title;
    private String description;
    private String primitive;
    private String spec;             // the proposed WidgetSpec JSON
    private String status = PENDING;
    private String origin;           // assistant | workbench
    private String sessionId;        // the assistant conversation it came from
    private String layoutId;
    private String pageId;           // the page it was built on: its parameters are the sample values
    private Map<String, String> params = new LinkedHashMap<>();
    private String submittedBy;      // the actor (e.g. demo-builder): only they, or a Reviewer, can revise it
    private String submittedByRole;
    private Integer approvedVersion;
    private List<Event> history = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    /** submitted | resubmitted | approved | sent_back (feedback & revise) | rejected */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Event {
        private String type;
        private String role;
        private String comment;
        private Instant at;
    }

    public boolean open() { return OPEN.contains(status); }

    /** The approver's latest comment (a send-back or a rejection), for the builder. */
    public String lastComment() {
        for (int i = history.size() - 1; i >= 0; i--) {
            Event e = history.get(i);
            if (e.getComment() != null && !e.getComment().isBlank()) return e.getComment();
        }
        return null;
    }
}
