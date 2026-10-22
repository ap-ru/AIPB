package com.aipb.assistant;

import com.aipb.assistant.llm.LlmGateway;
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

    public record CreateRequest(String layoutId, String pageId, Map<String, String> params) {}
    public record MessageRequest(String text, Map<String, String> params) {}
    public record ApproveRequest(Boolean confirmUnverified) {}

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("enabled", gateway.enabled(), "model", props.getModel(), "effort", props.getEffort());
    }

    @PostMapping("/sessions")
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        if (req == null || req.pageId() == null) throw new IllegalArgumentException("pageId is required");
        return view(orchestrator.create(req.layoutId(), req.pageId(), req.params()));
    }

    @GetMapping("/sessions/{id}")
    public Map<String, Object> get(@PathVariable String id) { return view(orchestrator.get(id)); }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@PathVariable String id, @RequestBody MessageRequest req) {
        orchestrator.get(id); // 404 before opening the stream
        return stream(events -> orchestrator.message(id, req == null ? null : req.text(), req == null ? null : req.params(), events));
    }

    @PostMapping(value = "/sessions/{id}/verify", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter verify(@PathVariable String id) {
        orchestrator.get(id);
        return stream(events -> orchestrator.verify(id, events));
    }

    @PostMapping("/sessions/{id}/approve")
    public Map<String, Object> approve(@PathVariable String id, @RequestBody(required = false) ApproveRequest req) {
        return orchestrator.approve(id, req != null && Boolean.TRUE.equals(req.confirmUnverified()));
    }

    /** The inspector: every stage run with its model, effort, tokens, cost, tool calls and context manifest. */
    @GetMapping("/sessions/{id}/runs")
    public List<AssistantSession.StageRun> runs(@PathVariable String id) { return orchestrator.get(id).getRuns(); }

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

    /** The session as the UI needs it: turns, status, and the proposal as JSON. */
    private Map<String, Object> view(AssistantSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("pageId", s.getPageId());
        m.put("params", s.getParams());
        m.put("status", s.getStatus());
        m.put("stage", s.getStage());
        m.put("reviewState", s.getReviewState());
        m.put("turns", s.getTurns());
        m.put("proposal", json(s.getProposal()));
        m.put("placedDefinitionId", s.getPlacedDefinitionId());
        double cost = s.getRuns().stream().mapToDouble(r -> r.getCostUsd() == null ? 0 : r.getCostUsd()).sum();
        m.put("costUsd", Math.round(cost * 10000) / 10000.0);
        return m;
    }

    private JsonNode json(String s) {
        if (s == null) return null;
        try { return mapper.readTree(s); } catch (IOException e) { return null; }
    }
}
