package com.aipb.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A request: static system blocks (cached - they must be byte-identical across calls), tool definitions, the
 * conversation so far, and optionally a JSON schema the final text must follow (structured outputs).
 */
public record LlmRequest(String stage, String model, String effort, long maxTokens, List<String> system,
                         List<ToolDef> tools, List<LlmMessage> messages, JsonNode outputSchema) {

    /** A user-defined tool. {@code strict} guarantees schema-valid input (the schema must close every object). */
    public record ToolDef(String name, String description, JsonNode inputSchema, boolean strict) {}

    public LlmRequest withMaxTokens(long n) {
        return new LlmRequest(stage, model, effort, n, system, tools, messages, outputSchema);
    }
}
