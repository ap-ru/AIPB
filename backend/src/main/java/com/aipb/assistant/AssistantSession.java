package com.aipb.assistant;

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

/**
 * One widget-assistant conversation on a page (aipb.assistant_sessions). Holds the banker's turns, the artifacts
 * each stage produced (brief, data, view, spec, proposal, review - stored as JSON text because pipelines are full of
 * $-prefixed keys), and an audit of every stage run: model, effort, tokens, tool calls and the manifest of exactly
 * what context the model was given.
 */
@Data
@NoArgsConstructor
@Document("assistant_sessions")
public class AssistantSession {

    public static final String RUNNING = "running";
    public static final String AWAITING_USER = "awaiting_user";
    public static final String PROPOSED = "proposed";
    public static final String PLACED = "placed";
    public static final String FAILED = "failed";

    @Id
    private String id;
    private String layoutId;
    private String pageId;
    /** The page's parameter values when the latest message was sent (e.g. the selected clientId) - the sample values. */
    private Map<String, String> params = new LinkedHashMap<>();
    private String status = AWAITING_USER;
    private String stage;
    private List<Turn> turns = new ArrayList<>();

    private String brief;     // IntakeStage output (the brief object)
    private String data;      // BuildStage artifact (accepted submit_data + preview)
    private String view;      // PresentStage output
    private String spec;      // the assembled widget spec
    private String proposal;  // what the banker sees and approves
    private String review;    // ReviewStage output, when one was requested
    private String reviewState = "none"; // none | passed | failed

    private List<StageRun> runs = new ArrayList<>();
    private String placedDefinitionId;  // a reused widget placed on the page, or the widget an approval produced

    // maker-checker: the submission this conversation's widget went to, and where it stands
    private String submissionId;
    private String approval;            // null until submitted, then the submission's status (pending, changes_requested, approved, rejected)
    private Integer approvedVersion;
    private String approverFeedback;    // the latest send-back comment, until the widget is resubmitted

    // modifying a library widget: which one, from which version, and its spec then
    private String modifies;
    private Integer modifiesVersion;
    private String modifiesTitle;
    private String baseSpec;
    /** The conversations that made the earlier versions of the widget being modified, oldest first (read-only history). */
    private List<Earlier> earlier = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Earlier {
        private String sessionId;
        private Integer version;   // the version that conversation's widget was approved as
        private String title;
        private List<Turn> turns;
    }
    private Instant createdAt;
    private Instant updatedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Turn {
        private String role;   // user | assistant | approver
        private String text;
        private String kind;   // message | clarify | answer | decline | reuse | proposal | error
        private Instant at;
    }

    @Data @NoArgsConstructor
    public static class StageRun {
        private String stage;
        private String model;
        private String effort;
        private Instant startedAt;
        private long ms;
        private int calls;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private Double costUsd;
        private List<ToolCall> toolCalls = new ArrayList<>();
        private List<ContextAssembler.ManifestEntry> manifest = new ArrayList<>();
        private String userContext;
        private String outcome;
        private String error;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ToolCall {
        private String name;
        private boolean ok;
        private long ms;
        private String summary;
    }
}
