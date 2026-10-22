package com.aipb.assistant;

import com.aipb.assistant.llm.LlmGateway;
import com.aipb.assistant.llm.LlmReply;
import com.aipb.assistant.llm.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/** A scripted Claude: replies are queued per stage and every request is recorded, so tests can see what was sent. */
class FakeGateway implements LlmGateway {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, Deque<LlmReply>> script = new HashMap<>();
    final List<LlmRequest> requests = new ArrayList<>();

    FakeGateway on(String stage, LlmReply... replies) {
        script.computeIfAbsent(stage, k -> new ArrayDeque<>()).addAll(List.of(replies));
        return this;
    }

    @Override
    public boolean enabled() { return true; }

    @Override
    public LlmReply send(LlmRequest request) {
        requests.add(request);
        Deque<LlmReply> q = script.get(request.stage());
        if (q == null || q.isEmpty()) throw new AssertionError("no scripted reply left for stage " + request.stage());
        return q.poll();
    }

    List<String> stagesCalled() { return requests.stream().map(LlmRequest::stage).toList(); }

    // ------------------------------------------------------------------ reply builders
    static final LlmReply.Usage USAGE = new LlmReply.Usage(1000, 200, 5000, 0);

    static LlmReply json(String json) {
        return new LlmReply("end_turn", json, List.of(), USAGE, null, "raw");
    }

    static LlmReply text(String text) {
        return new LlmReply("end_turn", text, List.of(), USAGE, null, "raw");
    }

    static LlmReply tool(String name, String inputJson) {
        try {
            JsonNode in = MAPPER.readTree(inputJson);
            return new LlmReply("tool_use", "", List.of(new LlmReply.ToolUse("tu_" + UUID.randomUUID(), name, in)), USAGE, null, "raw");
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    static LlmReply refusal(String category) {
        return new LlmReply("refusal", "", List.of(), USAGE, category, "raw");
    }
}
