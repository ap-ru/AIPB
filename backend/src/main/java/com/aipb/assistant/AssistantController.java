package com.aipb.assistant;

import com.aipb.assistant.llm.LlmGateway;
import com.aipb.identity.Actor;
import com.aipb.skills.SkillTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The widget assistant API. A message or a verify request returns a server-sent event stream (stage, activity,
 * message, proposal, error, done); the run keeps going and is saved if the client disconnects, and GET on the
 * session recovers its state. The runs endpoint is the inspector: what each stage was given and what it did.
 * Only roles that can build widgets may use it, and a conversation belongs to the actor who started it.
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantOrchestrator orchestrator;
    private final LlmGateway gateway;
    private final AssistantProperties props;
    private final ObjectMapper mapper;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /**
     * {@code templateId}: the Skills Studio template to build from (null: Custom widget); {@code inputs}: its form values.
     * {@code modifies}: a library widget id, to start a conversation that changes it (always a Custom widget run).
     */
    public record CreateRequest(String layoutId, String pageId, Map<String, String> params, String templateId, Map<String, String> inputs,
                                String modifies) {}
    public record MessageRequest(String text, Map<String, String> params) {}
    public record ApproveRequest(Boolean confirmUnverified, String note) {}
    public record SubmitRequest(String note) {}

    @GetMapping("/status")
    public Map<String, Object> status(Actor actor) {
        return Map.of("enabled", gateway.enabled(), "allowed", actor.role().canUseAssistant(), "model", props.getModel(), "effort", props.getEffort());
    }

    @PostMapping("/sessions")
    public Map<String, Object> create(@RequestBody CreateRequest req, Actor actor) {
        actor.requireAuthor();
        if (req == null || req.pageId() == null) throw new IllegalArgumentException("pageId is required");
        if (req.modifies() != null && !req.modifies().isBlank())
            return view(orchestrator.createModify(req.layoutId(), req.pageId(), req.params(), req.modifies(), actor.id()));
        return view(orchestrator.create(req.layoutId(), req.pageId(), req.params(), actor.id(), req.templateId(), req.inputs()));
    }

    /** A conversation: its owner continues it; a Reviewer can read any, to see how a submitted widget was built. */
    @GetMapping("/sessions/{id}")
    public Map<String, Object> get(@PathVariable String id, Actor actor) {
        return view(actor.role().canReview() ? orchestrator.get(id) : owned(id, actor));
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@PathVariable String id, @RequestBody MessageRequest req, Actor actor) {
        owned(id, actor); // 403/404 before opening the stream
        return stream(events -> orchestrator.message(id, req == null ? null : req.text(), req == null ? null : req.params(), events));
    }

    @PostMapping(value = "/sessions/{id}/verify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter verify(@PathVariable String id, Actor actor) {
        owned(id, actor);
        return stream(events -> orchestrator.verify(id, events));
    }

    /**
     * Add & share: saves the new widget to the actor's personal library, places it where only they see it, and shares it
     * for review (a Reviewer's is approved and goes live); or places a widget reused from the library.
     */
    @PostMapping("/sessions/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest req, Actor actor) {
        owned(id, actor);
        return orchestrator.approve(id, req != null && Boolean.TRUE.equals(req.confirmUnverified()), req == null ? null : req.note(), actor);
    }

    /** Submits the widget the conversation added to the global library, for a Reviewer to approve. */
    @PostMapping("/sessions/{id}/submit")
    public Map<String, Object> submit(@PathVariable String id, @RequestBody(required = false) SubmitRequest req, Actor actor) {
        owned(id, actor);
        return orchestrator.submit(id, req == null ? null : req.note(), actor);
    }

    /** The inspector: every stage run with its model, effort, tokens, cost, tool calls and context manifest. */
    @GetMapping("/sessions/{id}/runs")
    public List<AssistantSession.StageRun> runs(@PathVariable String id, Actor actor) {
        return (actor.role().canReview() ? orchestrator.get(id) : owned(id, actor)).getRuns();
    }

    private AssistantSession owned(String id, Actor actor) {
        return orchestrator.get(id, actor.requireAuthor().id());
    }

    private interface Run { void run(AssistantEvents events); }

    private SseEmitter stream(Run run) {
        SseEmitter emitter = new SseEmitter((long) (props.getRunTimeoutSeconds() + 60) * 1000);
        AssistantEvents events = (event, data) -> {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                log.debug("assistant client went away: {}", e.getMessage());
            }
        };
        pool.submit(() -> {
            try {
                run.run(events);
            } catch (RuntimeException e) {
                events.emit("error", Map.of("message", String.valueOf(e.getMessage())));
                events.emit("done", Map.of("status", "error"));
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    /** The session as the UI needs it: its template and stages, turns, status, and the proposal as JSON. */
    private Map<String, Object> view(AssistantSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("pageId", s.getPageId());
        m.put("params", s.getParams());
        SkillTemplate t = orchestrator.template(s);
        Map<String, Object> template = new LinkedHashMap<>();
        template.put("id", t.id());
        template.put("version", t.version());
        template.put("kind", t.kind());
        template.put("title", t.title());
        template.put("icon", t.icon());
        template.put("suggestions", t.suggestions());
        m.put("template", template);
        m.put("inputs", s.getInputs());
        m.put("stages", orchestrator.stageLabels(s));
        m.put("status", s.getStatus());
        m.put("stage", s.getStage());
        m.put("reviewState", s.getReviewState());
        m.put("turns", s.getTurns());
        m.put("proposal", json(s.getProposal()));
        m.put("placedDefinitionId", s.getPlacedDefinitionId());
        m.put("submissionId", s.getSubmissionId());
        m.put("approval", s.getApproval());
        m.put("approvedVersion", s.getApprovedVersion());
        m.put("reviewerFeedback", s.getReviewerFeedback());
        m.put("owner", s.getOwner());
        m.put("modifies", s.getModifies());
        m.put("modifiesVersion", s.getModifiesVersion());
        m.put("modifiesTitle", s.getModifiesTitle());
        m.put("earlier", s.getEarlier());
        double cost = s.getRuns().stream().mapToDouble(r -> r.getCostUsd() == null ? 0 : r.getCostUsd()).sum();
        m.put("costUsd", Math.round(cost * 10000) / 10000.0);
        return m;
    }

    private JsonNode json(String s) {
        if (s == null) return null;
        try { return mapper.readTree(s); } catch (IOException e) { return null; }
    }
}
