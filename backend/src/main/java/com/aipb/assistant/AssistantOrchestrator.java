package com.aipb.assistant;

import com.aipb.assistant.llm.LlmException;
import com.aipb.assistant.stages.*;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceException;
import com.aipb.widget.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
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
 * message -> INTAKE -> proceed -> BUILD -> PRESENT -> proposal -> submitted for approval (an approver's is approved)
 *                   -> clarify / answer / decline / reuse (reply, no build; a reused widget is placed as it is)
 *                   -> verify (opt-in) -> REVIEW -> [findings -> BUILD -> PRESENT]
 * approver: sends back -> the comment joins the conversation -> the builder's next message revises -> resubmitted
 * </pre>
 * A revision that only changes the display re-runs PRESENT alone. Stages see each other's artifacts, never their
 * transcripts. The session is saved after every stage, and every stage run is recorded (tokens, cost, tool calls,
 * the manifest of what the model saw). A conversation can also start from a library widget, to change it
 * ({@link #createModify}).
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
    private final WidgetWorkflow workflow;
    private final WidgetDefinitionRepository definitions;
    private final WidgetPlacement placement;
    private final LayoutService layouts;
    private final AssistantProperties props;
    private final ObjectMapper mapper;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** The session submitting on this thread: its own decisions are recorded by {@link #submit}, not the listener. */
    private final ThreadLocal<String> submitting = new ThreadLocal<>();

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

    public AssistantSession create(String layoutId, String pageId, Map<String, String> params) {
        AssistantSession s = new AssistantSession();
        s.setId(UUID.randomUUID().toString());
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

    /**
     * A conversation that changes a library widget. It starts from the widget's current version: the brief, data and
     * design of the conversation that made it when there is one, otherwise its spec and a run of its data, so a request
     * is a revision of it (and a display-only change keeps its data as it is). Runs on a page that supplies what the
     * widget needs - the given one when it can.
     */
    public AssistantSession createModify(String layoutId, String pageId, Map<String, String> params, String widgetId) {
        WidgetDefinition d = library.get(widgetId);
        workflow.openFor(widgetId).ifPresent(o -> {
            throw new WidgetWorkflow.Conflict("\"" + d.getTitle() + "\" already has a change waiting for approval; open that one from Skills instead");
        });
        JsonNode spec = library.spec(d);
        Map<String, String> sample = new LinkedHashMap<>(library.sampleParams(spec));
        if (params != null) params.forEach((k, v) -> { if (v != null && !v.isBlank()) sample.put(k, v); });
        AssistantSession s = create(layoutId, pageFor(layoutId, pageId, d), sample);
        s.setModifies(d.getId());
        s.setModifiesVersion(d.getVersion());
        s.setModifiesTitle(d.getTitle());
        s.setBaseSpec(spec.toString());
        s.setSpec(spec.toString());

        AssistantSession origin = sessions.findById(spec.path("provenance").path("sessionId").asText("-")).orElse(null);
        if (origin != null) {
            // the conversations behind the earlier versions, shown read-only above the new one
            s.getEarlier().addAll(origin.getEarlier());
            String title = origin.getProposal() != null ? read(origin.getProposal()).path("title").asText(d.getTitle()) : d.getTitle();
            s.getEarlier().add(new AssistantSession.Earlier(origin.getId(), origin.getApprovedVersion(), title, origin.getTurns()));
        }
        if (origin != null && origin.getBrief() != null && origin.getData() != null && origin.getView() != null) {
            s.setBrief(origin.getBrief());
            s.setData(origin.getData());
            s.setView(origin.getView());
        } else {
            s.setData(dataOf(spec, s));
        }
        s.getTurns().add(new AssistantSession.Turn("assistant", "You're changing \"" + d.getTitle() + "\" (v" + d.getVersion()
                + "). Tell me what to change; the new version goes for approval and the current one stays live until it's approved.",
                "message", Instant.now()));
        return sessions.save(s);
    }

    /**
     * Sends the proposed widget for approval - a resubmission when the conversation's widget was sent back, a change
     * when it modifies a library widget - or, for a widget reused from the library, places it on the page. An
     * approver's widget is approved as it is submitted. Nothing new reaches a page here.
     */
    public Map<String, Object> submit(String sessionId, boolean confirmUnverified, Role role) {
        return withLock(sessionId, () -> {
            AssistantSession s = get(sessionId);
            if (s.getProposal() == null) throw new IllegalArgumentException("there is nothing to submit yet");
            JsonNode proposal = read(s.getProposal());
            Map<String, Object> out = new LinkedHashMap<>();
            if ("reuse".equals(proposal.path("kind").asText())) {
                String definitionId = proposal.path("definitionId").asText();
                Layout layout = placement.placeDefinition(s.getLayoutId(), s.getPageId(), definitionId, "assistant");
                s.setPlacedDefinitionId(definitionId);
                s.setStatus(AssistantSession.PLACED);
                s.getTurns().add(new AssistantSession.Turn("assistant", "Added \"" + proposal.path("title").asText() + "\" to the page.", "message", Instant.now()));
                s.setUpdatedAt(Instant.now());
                sessions.save(s);
                out.put("definitionId", definitionId);
                out.put("layout", layout);
                return out;
            }
            if ("failed".equals(s.getReviewState()) && !confirmUnverified)
                throw new ConfirmationRequired("the review could not verify this widget; confirm to submit it anyway");
            ObjectNode spec = (ObjectNode) read(s.getSpec());
            ObjectNode prov = spec.putObject("provenance");
            prov.put("sessionId", s.getId());
            JsonNode brief = s.getBrief() == null ? null : read(s.getBrief());
            if (brief != null) prov.put("question", brief.path("question").asText());
            prov.put("reviewed", s.getReviewState());

            WidgetSubmission sub;
            WidgetSubmission previous = s.getSubmissionId() == null ? null : workflow.get(s.getSubmissionId());
            submitting.set(s.getId());
            try {
                if (previous != null && previous.open()) {
                    sub = workflow.resubmit(previous.getId(), spec, role);
                } else if (previous != null && WidgetSubmission.REJECTED.equals(previous.getStatus())) {
                    throw new WidgetWorkflow.Conflict("this widget was rejected; start a new chat to build another");
                } else {
                    // after an approval, a further change in the same conversation is a change to that widget
                    String modifies = previous != null ? previous.getWidgetId() : s.getModifies();
                    if (modifies == null) spec.put("id", uniqueId(spec.path("id").asText()));
                    sub = workflow.submit(new WidgetWorkflow.Submit(spec, "assistant", s.getId(), s.getLayoutId(), s.getPageId(),
                            s.getParams(), modifies), role);
                }
            } finally {
                submitting.remove();
            }
            s.setSubmissionId(sub.getId());
            s.setApproval(sub.getStatus());
            s.setApproverFeedback(null);
            String title = "\"" + sub.getTitle() + "\"";
            if (WidgetSubmission.APPROVED.equals(sub.getStatus())) {
                s.setApprovedVersion(sub.getApprovedVersion());
                s.setPlacedDefinitionId(sub.getWidgetId());
                s.getTurns().add(new AssistantSession.Turn("assistant", "Approved " + title + " as v" + sub.getApprovedVersion()
                        + ". It's in the library now - add it to a page from here or from Skills.", "approved", Instant.now()));
            } else {
                s.getTurns().add(new AssistantSession.Turn("assistant", (previous != null && previous.open() ? "Resubmitted " : "Sent ")
                        + title + " for approval. You'll see the decision here and in Skills.", "submitted", Instant.now()));
            }
            s.setUpdatedAt(Instant.now());
            sessions.save(s);
            out.put("submission", workflow.item(sub));
            return out;
        });
    }

    /** An approver's decision, shown in the conversation the widget came from (decisions taken in it are recorded by submit). */
    @EventListener
    public void onDecision(WidgetWorkflow.Decision d) {
        WidgetSubmission sub = d.submission();
        if (sub.getSessionId() == null || sub.getSessionId().equals(submitting.get())) return;
        if (!Set.of("approved", "sent_back", "rejected").contains(d.type())) return;
        AssistantSession s = sessions.findById(sub.getSessionId()).orElse(null);
        if (s == null) return;
        String title = "\"" + sub.getTitle() + "\"";
        String note = d.comment() == null ? "" : " " + d.comment();
        switch (d.type()) {
            case "approved" -> {
                s.setApprovedVersion(sub.getApprovedVersion());
                s.setPlacedDefinitionId(sub.getWidgetId());
                s.getTurns().add(new AssistantSession.Turn("approver", "Approved " + title + " as v" + sub.getApprovedVersion() + "." + note, "approved", Instant.now()));
            }
            case "sent_back" -> {
                s.setApproverFeedback(d.comment());
                s.getTurns().add(new AssistantSession.Turn("approver", "Sent back with changes needed:" + note, "sent_back", Instant.now()));
            }
            default -> s.getTurns().add(new AssistantSession.Turn("approver", "Rejected " + title + "." + note, "rejected", Instant.now()));
        }
        s.setApproval(sub.getStatus());
        s.setUpdatedAt(Instant.now());
        sessions.save(s);
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

        String request = s.getApproverFeedback() == null ? text
                : text + "\n\nThe approver sent the widget back with this feedback: " + s.getApproverFeedback();
        JsonNode data;
        if (presentationOnly) {
            data = read(s.getData());
        } else {
            BuildStage.Revision rev = s.getData() == null ? null : new BuildStage.Revision(s.getData(), request, null);
            data = stage(s, "build", events, rec -> build.run(brief, rev, ctx, rec, deadline, events));
            s.setData(data.toString());
            s.setReviewState("none");
            s.setReview(null);
        }
        presentAndPropose(s, brief, data, presentationOnly ? request : null, events, deadline, false);
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
        if (s.getModifies() != null && "new".equals(proposal.path("kind").asText())) {
            proposal.put("modifies", s.getModifies());
            proposal.put("baseVersion", s.getModifiesVersion());
        }
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
        for (int n = 2; workflow.idTaken(id); n++) id = base + "-" + n;
        return id;
    }

    /** The given page if it supplies every parameter the widget needs, else the first page that does. */
    private String pageFor(String layoutId, String pageId, WidgetDefinition d) {
        Layout layout = layouts.get(layoutId == null ? "default" : layoutId);
        List<String> needs = d.getParams() == null ? List.of() : d.getParams().stream().filter(p -> !"asOfDate".equals(p)).toList();
        java.util.function.Predicate<Layout.Page> fits = p -> needs.stream().allMatch(n -> p.param(n) != null);
        Layout.Page given = pageId == null ? null : layout.page(pageId);
        if (given != null && fits.test(given)) return given.getId();
        return layout.getPages().stream().filter(fits).map(Layout.Page::getId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no page supplies what \"" + d.getTitle() + "\" needs (" + String.join(", ", needs) + ")"));
    }

    /**
     * A data artifact for a widget that was not made in a conversation (stock, workbench): its query or pipeline, and
     * its columns and first rows from a live run - what BUILD would have produced, so PRESENT can redesign it as it is.
     * Null when it cannot run here; the first change then rebuilds the data.
     */
    private String dataOf(JsonNode spec, AssistantSession s) {
        ValidationReport r = workflow.preview(spec, s.getLayoutId(), s.getPageId(), s.getParams());
        if (!r.ok() || r.preview() == null) return null;
        ObjectNode a = mapper.createObjectNode();
        JsonNode data = spec.path("data");
        ArrayNode columns = mapper.createArrayNode();
        if (data.has("query")) {
            a.set("query", data.get("query"));
            if (data.has("columns")) a.set("overrides", data.get("columns"));
            r.preview().columns().forEach(c -> {
                ObjectNode col = columns.addObject();
                col.put("name", c.name());
                col.put("type", c.type());
                col.put("unit", c.unitOrDefault());
                col.put("format", c.formatOrDefault());
                col.put("label", c.label() == null ? c.name() : c.label());
                if (c.tones() != null) col.set("tones", mapper.valueToTree(c.tones()));
            });
        } else {
            JsonNode custom = data.path("custom");
            a.set("collection", custom.path("collection"));
            a.set("pipeline", custom.path("pipeline"));
            custom.path("columns").forEach(c -> columns.add(c.deepCopy()));
            if (spec.has("params")) a.set("params", spec.get("params"));
        }
        a.set("columns", columns);
        a.set("preview", mapper.valueToTree(r.preview()));
        a.set("warnings", mapper.valueToTree(r.warnings()));
        a.set("sampleParams", mapper.valueToTree(r.sampleParams()));
        return a.toString();
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
