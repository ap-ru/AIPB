package com.aipb.assistant;

import com.aipb.assistant.llm.LlmException;
import com.aipb.assistant.stages.*;
import com.aipb.model.Layout;
import com.aipb.source.SourceException;
import com.aipb.widget.WidgetDefinition;
import com.aipb.widget.WidgetDefinitionRepository;
import com.aipb.widget.WidgetLibrary;
import com.aipb.widget.WidgetPlacement;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Runs the widget assistant: a code-orchestrated pipeline of narrow stages, each a separate Claude conversation with
 * one objective and a deterministic gate.
 * <pre>
 * message -> INTAKE -> proceed -> BUILD -> PRESENT -> proposal -> approved -> personal library + placed -> submit for review
 *                   -> clarify / answer / decline / reuse (reply, no build)
 *                   -> verify (opt-in) -> REVIEW -> [findings -> BUILD -> PRESENT]
 * </pre>
 * A revision that only changes the display re-runs PRESENT alone. Stages see each other's artifacts, never their
 * transcripts. The session is saved after every stage, and every stage run is recorded (tokens, cost, tool calls,
 * the manifest of what the model saw).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantOrchestrator {

    private final AssistantSessionRepository sessions;
    private final IntakeStage intake;
    private final BuildStage build;
    private final ReviewStage review;
    private final PresentStage present;
    private final ProposalBuilder proposals;
    private final WidgetLibrary library;
    private final WidgetDefinitionRepository definitions;
    private final WidgetPlacement placement;
    private final AssistantProperties props;
    private final ObjectMapper mapper;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    static final Map<String, String> LABELS = Map.of("intake", "Understanding the request", "build", "Finding the data",
            "review", "Checking the numbers", "present", "Designing the widget");

    /** Adding a widget the requested review could not verify needs the banker's explicit confirmation. */
    public static class ConfirmationRequired extends RuntimeException {
        public ConfirmationRequired(String message) { super(message); }
    }

    /** The session is busy with another run. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("the assistant is still working on the previous message"); }
    }

    public AssistantSession create(String layoutId, String pageId, Map<String, String> params, String owner) {
        AssistantSession s = new AssistantSession();
        s.setId(UUID.randomUUID().toString());
        s.setOwner(owner);
        s.setLayoutId(layoutId == null ? "default" : layoutId);
        s.setPageId(pageId);
        if (params != null) s.setParams(new LinkedHashMap<>(params));
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        return sessions.save(s);
    }

    public AssistantSession get(String id) {
        return sessions.findById(id).orElseThrow(() -> new NoSuchElementException("assistant session " + id));
    }

    /** Handles a banker message end to end, streaming progress to {@code events}. */
    public void message(String sessionId, String text, Map<String, String> params, AssistantEvents events) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("the message is empty");
        withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (params != null && !params.isEmpty()) s.setParams(new LinkedHashMap<>(params));
            s.getTurns().add(new AssistantSession.Turn("user", text.trim(), "message", Instant.now()));
            run(s, events, () -> handleMessage(s, text.trim(), events));
            return null;
        });
    }

    /** The banker asked to verify the current proposal (the opt-in review). */
    public void verify(String sessionId, AssistantEvents events) {
        withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (s.getSpec() == null) throw new IllegalArgumentException("there is no proposed widget to verify");
            s.getTurns().add(new AssistantSession.Turn("user", "Verify the numbers.", "message", Instant.now()));
            run(s, events, () -> runReview(s, events));
            return null;
        });
    }

    /** The session, if it belongs to {@code actorId}; someone else's conversation is reported as not found. */
    public AssistantSession get(String id, String actorId) {
        AssistantSession s = get(id);
        if (s.getOwner() != null && !s.getOwner().equals(actorId)) throw new NoSuchElementException("assistant session " + id);
        return s;
    }

    /**
     * Saves the proposed widget to the owner's personal library and places it on the session's page; returns the
     * layout. A new widget ends with an offer to submit it to the global library for review.
     */
    public Map<String, Object> approve(String sessionId, boolean confirmUnverified) {
        return withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (s.getProposal() == null) throw new IllegalArgumentException("there is nothing to add yet");
            JsonNode proposal = read(s.getProposal());
            String definitionId;
            if ("reuse".equals(proposal.path("kind").asText())) {
                definitionId = proposal.path("definitionId").asText();
            } else {
                if ("failed".equals(s.getReviewState()) && !confirmUnverified)
                    throw new ConfirmationRequired("the review could not verify this widget; confirm to add it anyway");
                ObjectNode spec = (ObjectNode) read(s.getSpec());
                spec.put("id", uniqueId(spec.path("id").asText()));
                ObjectNode prov = spec.putObject("provenance");
                prov.put("sessionId", s.getId());
                JsonNode brief = s.getBrief() == null ? null : read(s.getBrief());
                if (brief != null) prov.put("question", brief.path("question").asText());
                prov.put("reviewed", s.getReviewState());
                definitionId = library.create(spec, "assistant", s.getOwner(), WidgetDefinition.PERSONAL,
                        s.getLayoutId(), s.getPageId(), s.getParams()).definition().getId();
            }
            Layout layout = placement.placeDefinition(s.getLayoutId(), s.getPageId(), definitionId, "assistant");
            s.setPlacedDefinitionId(definitionId);
            s.setStatus(AssistantSession.PLACED);
            String title = proposal.path("title").asText();
            s.getTurns().add("reuse".equals(proposal.path("kind").asText())
                    ? new AssistantSession.Turn("assistant", "Added \"" + title + "\" to the page.", "message", Instant.now())
                    : new AssistantSession.Turn("assistant", "Added \"" + title + "\" to the page and saved it to your personal library. "
                        + "Submit it to the global library so a Reviewer can approve it for everyone?", "submit-prompt", Instant.now()));
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("definitionId", definitionId);
            out.put("layout", layout);
            return out;
        });
    }

    /** Submits the widget this conversation created to the global library, for a Reviewer to approve. */
    public Map<String, Object> submit(String sessionId, String note) {
        return withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (!AssistantSession.PLACED.equals(s.getStatus()) || s.getPlacedDefinitionId() == null)
                throw new IllegalArgumentException("add the widget before submitting it for review");
            JsonNode proposal = read(s.getProposal());
            if ("reuse".equals(proposal.path("kind").asText()))
                throw new IllegalArgumentException("this widget is already in the library");
            if ("pending".equals(s.getSubmission())) throw new IllegalArgumentException("this widget is already waiting for review");
            WidgetDefinition d = library.submitForReview(s.getPlacedDefinitionId(), s.getOwner(), note);
            s.setSubmission("pending");
            s.getTurns().add(new AssistantSession.Turn("assistant", "Submitted \"" + d.getTitle() + "\" for review. It stays in your "
                    + "personal library until a Reviewer approves it for the global library.", "message", Instant.now()));
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("definitionId", d.getId());
            out.put("review", d.reviewStatus());
            return out;
        });
    }

    // ------------------------------------------------------------------ flows
    private void handleMessage(AssistantSession s, String text, AssistantEvents events) {
        Instant deadline = deadline();
        JsonNode out = stage(s, "intake", events, rec -> intake.run(s, text, rec, deadline));
        String outcome = out.path("outcome").asText();
        String message = out.path("message").asText();
        switch (outcome) {
            case "clarify", "answer", "decline" -> reply(s, events, message, outcome);
            case "reuse" -> {
                var d = definitions.findById(out.path("reuseDefinitionId").asText()).orElseThrow();
                ObjectNode p = proposals.reuse(d, s.getParams());
                propose(s, events, p, message);
            }
            case "verify" -> runReview(s, events);
            default -> buildAndPresent(s, out, text, events, deadline);
        }
    }

    private void buildAndPresent(AssistantSession s, JsonNode out, String text, AssistantEvents events, Instant deadline) {
        JsonNode brief = out.path("brief");
        List<String> changed = new ArrayList<>();
        out.path("changed").forEach(c -> changed.add(c.asText()));
        boolean presentationOnly = s.getSpec() != null && s.getData() != null && !changed.isEmpty()
                && changed.stream().allMatch("presentation"::equals);
        s.setBrief(brief.toString());
        events.emit("message", Map.of("text", out.path("message").asText(), "kind", "ack"));
        AssistantTools.Context ctx = context(s);

        JsonNode data;
        if (presentationOnly) {
            data = read(s.getData());
        } else {
            BuildStage.Revision rev = s.getData() == null ? null : new BuildStage.Revision(s.getData(), text, null);
            data = stage(s, "build", events, rec -> build.run(brief, rev, ctx, rec, deadline, events));
            s.setData(data.toString());
            s.setReviewState("none");
            s.setReview(null);
        }
        presentAndPropose(s, brief, data, presentationOnly ? text : null, events, deadline, false);
    }

    private void runReview(AssistantSession s, AssistantEvents events) {
        Instant deadline = deadline();
        if (s.getSpec() == null || s.getData() == null) {
            reply(s, events, "There is no proposed widget to verify yet.", "answer");
            return;
        }
        JsonNode brief = read(s.getBrief());
        JsonNode data = read(s.getData());
        AssistantTools.Context ctx = context(s);
        ReviewStage.Result r = stage(s, "review", events, rec -> review.run(brief, data, ctx, rec, deadline, events));
        s.setReview(r.review().toString());
        if (r.passed()) {
            s.setReviewState("passed");
            ObjectNode p = (ObjectNode) read(s.getProposal());
            ObjectNode rv = p.putObject("review");
            rv.put("state", "passed");
            rv.put("summary", r.review().path("summary").asText());
            rv.set("checks", r.review().path("checks"));
            rv.set("independentChecks", r.review().path("independentChecks"));
            rv.set("issues", r.review().path("issues"));
            propose(s, events, p, "The review confirmed the numbers: " + r.review().path("summary").asText());
            return;
        }
        // the review found problems: rebuild with its findings, redesign, and flag the result as not verified
        s.setReviewState("failed");
        String findings = r.review().path("issues").toPrettyString();
        JsonNode rebuilt = stage(s, "build", events, rec -> build.run(brief, new BuildStage.Revision(s.getData(), null, findings), ctx, rec, deadline, events));
        s.setData(rebuilt.toString());
        presentAndPropose(s, brief, rebuilt, null, events, deadline, true);
    }

    private void presentAndPropose(AssistantSession s, JsonNode brief, JsonNode data, String changeRequest, AssistantEvents events,
                                   Instant deadline, boolean revisedAfterReview) {
        AssistantTools.Context ctx = context(s);
        PresentStage.Result pr = stage(s, "present", events, rec -> present.run(brief, data, changeRequest, ctx, rec, deadline));
        s.setView(pr.design().toString());
        s.setSpec(pr.checked().spec().toString());
        JsonNode review = s.getReview() == null ? null : read(s.getReview());
        ObjectNode p = proposals.newWidget(brief, data, pr.checked().spec(), pr.checked().report(), s.getReviewState(), review, revisedAfterReview);
        String text = (revisedAfterReview ? "The review found problems, so I revised the data (not re-reviewed). " : "")
                + "Here's a proposal: \"" + p.path("title").asText() + "\" - " + p.path("description").asText();
        propose(s, events, p, text);
    }

    // ------------------------------------------------------------------ plumbing
    private interface StageCall<T> { T call(RunRecorder rec); }

    /** Runs one stage: streams start/finish, records the run, saves the session. */
    private <T> T stage(AssistantSession s, String name, AssistantEvents events, StageCall<T> call) {
        s.setStage(name);
        events.emit("stage", Map.of("stage", name, "label", LABELS.get(name), "status", "started"));
        RunRecorder rec = new RunRecorder(name, props);
        try {
            T out = call.call(rec);
            s.getRuns().add(rec.finish("ok", null));
            events.emit("stage", Map.of("stage", name, "label", LABELS.get(name), "status", "done"));
            return out;
        } catch (RuntimeException e) {
            s.getRuns().add(rec.finish("failed", e.getMessage()));
            events.emit("stage", Map.of("stage", name, "label", LABELS.get(name), "status", "failed"));
            throw e;
        } finally {
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
        }
    }

    private void run(AssistantSession s, AssistantEvents events, Runnable body) {
        s.setStatus(AssistantSession.RUNNING);
        sessions.save(s);
        try {
            body.run();
        } catch (StageFailure | LlmException | SourceException | IllegalArgumentException e) {
            log.info("assistant session {} stopped: {}", s.getId(), e.getMessage());
            fail(s, events, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("assistant session {} failed", s.getId(), e);
            fail(s, events, "Something went wrong: " + e.getMessage());
        } finally {
            s.setStage(null);
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
            events.emit("done", Map.of("status", s.getStatus()));
        }
    }

    private void reply(AssistantSession s, AssistantEvents events, String text, String kind) {
        s.getTurns().add(new AssistantSession.Turn("assistant", text, kind, Instant.now()));
        s.setStatus(AssistantSession.AWAITING_USER);
        events.emit("message", Map.of("text", text, "kind", kind));
    }

    private void propose(AssistantSession s, AssistantEvents events, ObjectNode proposal, String text) {
        s.setProposal(proposal.toString());
        s.getTurns().add(new AssistantSession.Turn("assistant", text, "proposal", Instant.now()));
        s.setStatus(AssistantSession.PROPOSED);
        events.emit("proposal", proposal);
    }

    private void fail(AssistantSession s, AssistantEvents events, String message) {
        s.getTurns().add(new AssistantSession.Turn("assistant", message, "error", Instant.now()));
        s.setStatus(s.getProposal() != null ? AssistantSession.PROPOSED : AssistantSession.FAILED);
        events.emit("error", Map.of("message", message));
    }

    private AssistantTools.Context context(AssistantSession s) {
        return new AssistantTools.Context(s.getLayoutId(), s.getPageId(), s.getParams());
    }

    private String uniqueId(String base) {
        String id = base;
        for (int n = 2; definitions.existsById(id); n++) id = base + "-" + n;
        return id;
    }

    private Instant deadline() { return Instant.now().plusSeconds(props.getRunTimeoutSeconds()); }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("stored assistant state is not valid JSON", e);
        }
    }

    private <T> T withLock(String sessionId, Supplier<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(sessionId, k -> new ReentrantLock());
        if (!lock.tryLock()) throw new BusyException();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }
}
