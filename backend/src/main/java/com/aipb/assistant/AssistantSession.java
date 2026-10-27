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
 * One assistant conversation on a page (aipb.assistant_sessions), building one kind of skill from one Skills Studio
 * template (null template: Custom widget - started before templates existed). Holds the banker's turns, the artifacts
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
    /** The actor who started the conversation; only they can continue it, and what it saves goes to their personal library. */
    private String owner;
    private String pageId;
    /** The page's parameter values when the latest message was sent (e.g. the selected clientId) - the sample values. */
    private Map<String, String> params = new LinkedHashMap<>();
    private String templateId;            // the Skills Studio template guiding the run
    private Integer templateVersion;
    private String kind;                  // the skill kind being built (the template's)
    private Map<String, String> inputs = new LinkedHashMap<>(); // the banker's answers to the template's inputs
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
    private String placedDefinitionId;
    private String submission = "none"; // none | pending: the placed widget was submitted to the global library for review
    private Instant createdAt;
    private Instant updatedAt;

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Turn {
        private String role;   // user | assistant
        private String text;
        private String kind;   // message | clarify | answer | decline | redirect | reuse | proposal | error
        private Instant at;
        private String templateId; // redirect: the template suggested instead

        public Turn(String role, String text, String kind, Instant at) { this(role, text, kind, at, null); }
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
