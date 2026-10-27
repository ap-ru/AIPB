package com.aipb.assistant;

import com.aipb.assistant.llm.LlmException;
import com.aipb.assistant.stages.*;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.skills.*;
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
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the assistant: a code-orchestrated pipeline of narrow stages, each a separate Claude conversation with one
 * objective and a deterministic gate. Every run builds one kind of skill, guided by the Skills Studio template the
 * session was started from (Custom widget when none was chosen).
 * <pre>
 * message -> INTAKE -> proceed -> the kind's stages (widget: BUILD -> PRESENT) -> proposal -> approved -> personal library + placed -> submit for review
 *                   -> clarify / answer / decline / reuse (reply, no build)
 *                   -> verify (opt-in) -> the kind's verification
 * </pre>
 * This class owns what every kind shares - Intake, locking, streaming, recording, approval; a {@link SkillKind} owns
 * the stages after Intake. Stages see each other's artifacts, never their transcripts. The session is saved after
 * every stage, and every stage run is recorded (tokens, cost, tool calls, the manifest of what the model saw).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantOrchestrator {

    private final AssistantSessionRepository sessions;
    private final IntakeStage intake;
    private final SkillTemplateRegistry templates;
    private final WidgetLibrary library;
    private final WidgetDefinitionRepository definitions;
    private final WidgetPlacement placement;
    private final LayoutService layouts;
    private final AssistantProperties props;
    private final ObjectMapper mapper;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    static final String INTAKE_LABEL = "Understanding the request";

    /** Adding a widget the requested review could not verify needs the banker's explicit confirmation. */
    public static class ConfirmationRequired extends RuntimeException {
        public ConfirmationRequired(String message) { super(message); }
    }

    /** The session is busy with another run. */
    public static class BusyException extends RuntimeException {
        public BusyException() { super("the assistant is still working on the previous message"); }
    }

    public AssistantSession create(String layoutId, String pageId, Map<String, String> params, String owner) {
        return create(layoutId, pageId, params, owner, null, null);
    }

    /**
     * A new conversation on a page, guided by a Skills Studio template (null: Custom widget) with the values the banker
     * gave for its inputs. The template must fit the page.
     */
    public AssistantSession create(String layoutId, String pageId, Map<String, String> params, String owner, String templateId,
                                   Map<String, String> inputs) {
        String layout = layoutId == null ? "default" : layoutId;
        SkillTemplate t = templates.forSession(templateId);
        Layout.Page page = layouts.get(layout).page(pageId);
        if (page == null) throw new NoSuchElementException("page " + pageId);
        if (!t.fits(page))
            throw new IllegalArgumentException("the " + t.title() + " template is for " + String.join(" or ", t.pageScopes())
                    + " pages; " + page.getTitle() + " is a " + SkillTemplate.pageScope(page) + " page");
        AssistantSession s = new AssistantSession();
        s.setId(UUID.randomUUID().toString());
        s.setOwner(owner);
        s.setLayoutId(layout);
        s.setPageId(pageId);
        if (params != null) s.setParams(new LinkedHashMap<>(params));
        s.setTemplateId(t.id());
        s.setTemplateVersion(t.version());
        s.setKind(t.kind());
        s.setInputs(templates.checkInputs(t, inputs));
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
            run(s, events, ops -> handleMessage(skillRun(s), text.trim(), ops));
            return null;
        });
    }

    /** The banker asked to verify the current proposal (the opt-in review). */
    public void verify(String sessionId, AssistantEvents events) {
        withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (s.getSpec() == null) throw new IllegalArgumentException("there is no proposed widget to verify");
            s.getTurns().add(new AssistantSession.Turn("user", "Verify the numbers.", "message", Instant.now()));
            run(s, events, ops -> { SkillRun r = skillRun(s); kind(r).verify(r, ops); });
            return null;
        });
    }

    /** The session, if it belongs to {@code actorId}; someone else's conversation is reported as not found. */
    public AssistantSession get(String id, String actorId) {
        AssistantSession s = get(id);
        if (s.getOwner() != null && !s.getOwner().equals(actorId)) throw new NoSuchElementException("assistant session " + id);
        return s;
    }

    /** The template guiding a session. */
    public SkillTemplate template(AssistantSession s) { return templates.forSession(s.getTemplateId()); }

    /** The stage labels of a session's kind, Intake first. */
    public Map<String, String> stageLabels(AssistantSession s) { return labels(kind(skillRun(s))); }

    /**
     * Saves the proposed skill to the owner's personal library and places it on the session's page; returns the
     * layout. A new skill ends with an offer to submit it to the global library for review.
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
                SkillRun r = skillRun(s);
                definitionId = kind(r).save(r);
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
    private void handleMessage(SkillRun r, String text, StageOps ops) {
        AssistantSession s = r.session();
        List<SkillTemplate> others = redirectTargets(r);
        JsonNode out = ops.stage("intake", rec -> intake.run(s, text, r.template(), others, rec, ops.deadline()));
        String outcome = out.path("outcome").asText();
        String message = out.path("message").asText();
        SkillKind kind = kind(r);
        switch (outcome) {
            case "clarify", "answer", "decline" -> ops.reply(message, outcome);
            case "redirect" -> {
                ops.reply(message, outcome);
                s.getTurns().get(s.getTurns().size() - 1).setTemplateId(out.path("suggestedTemplateId").asText());
            }
            case "reuse" -> {
                var d = definitions.findById(out.path("reuseDefinitionId").asText()).orElseThrow();
                ops.propose(kind.reuseProposal(d, s.getParams()), message);
            }
            case "verify" -> kind.verify(r, ops);
            default -> kind.proceed(r, out, text, ops);
        }
    }

    /**
     * The templates a templated run may send an out-of-scope request to: the others that fit the page. A Custom widget
     * run takes any request, so it has none (and its Intake context stays as it was before templates).
     */
    private List<SkillTemplate> redirectTargets(SkillRun r) {
        if (r.template().silentOn("intake")) return List.of();
        Layout.Page page = layouts.get(r.session().getLayoutId()).page(r.session().getPageId());
        return templates.forPage(page).stream().filter(t -> !t.id().equals(r.template().id())).toList();
    }

    // ------------------------------------------------------------------ plumbing
    private SkillRun skillRun(AssistantSession s) { return SkillRun.of(s, template(s)); }

    private SkillKind kind(SkillRun r) { return templates.kind(r.template()); }

    private static Map<String, String> labels(SkillKind kind) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("intake", INTAKE_LABEL);
        m.putAll(kind.stageLabels());
        return m;
    }

    private void run(AssistantSession s, AssistantEvents events, Consumer<StageOps> body) {
        s.setStatus(AssistantSession.RUNNING);
        sessions.save(s);
        try {
            body.accept(new Ops(s, events, labels(kind(skillRun(s))), Instant.now().plusSeconds(props.getRunTimeoutSeconds())));
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

    /** The orchestrator's side of one run, lent to the skill kind. */
    private final class Ops implements StageOps {
        private final AssistantSession s;
        private final AssistantEvents events;
        private final Map<String, String> labels;
        private final Instant deadline;

        Ops(AssistantSession s, AssistantEvents events, Map<String, String> labels, Instant deadline) {
            this.s = s;
            this.events = events;
            this.labels = labels;
            this.deadline = deadline;
        }

        @Override
        public <T> T stage(String name, StageCall<T> call) {
            String label = labels.getOrDefault(name, name);
            s.setStage(name);
            events.emit("stage", Map.of("stage", name, "label", label, "status", "started"));
            RunRecorder rec = new RunRecorder(name, props);
            try {
                T out = call.call(rec);
                s.getRuns().add(rec.finish("ok", null));
                events.emit("stage", Map.of("stage", name, "label", label, "status", "done"));
                return out;
            } catch (RuntimeException e) {
                s.getRuns().add(rec.finish("failed", e.getMessage()));
                events.emit("stage", Map.of("stage", name, "label", label, "status", "failed"));
                throw e;
            } finally {
                s.setUpdatedAt(Instant.now());
                sessions.save(s);
            }
        }

        @Override
        public void reply(String text, String kind) {
            s.getTurns().add(new AssistantSession.Turn("assistant", text, kind, Instant.now()));
            s.setStatus(AssistantSession.AWAITING_USER);
            events.emit("message", Map.of("text", text, "kind", kind));
        }

        @Override
        public void propose(ObjectNode proposal, String text) {
            SkillTemplate t = template(s);
            proposal.put("skillKind", t.kind());
            proposal.putObject("template").put("id", t.id()).put("version", t.version()).put("title", t.title());
            s.setProposal(proposal.toString());
            s.getTurns().add(new AssistantSession.Turn("assistant", text, "proposal", Instant.now()));
            s.setStatus(AssistantSession.PROPOSED);
            events.emit("proposal", proposal);
        }

        @Override
        public AssistantEvents events() { return events; }

        @Override
        public Instant deadline() { return deadline; }
    }

    private void fail(AssistantSession s, AssistantEvents events, String message) {
        s.getTurns().add(new AssistantSession.Turn("assistant", message, "error", Instant.now()));
        s.setStatus(s.getProposal() != null ? AssistantSession.PROPOSED : AssistantSession.FAILED);
        events.emit("error", Map.of("message", message));
    }

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
