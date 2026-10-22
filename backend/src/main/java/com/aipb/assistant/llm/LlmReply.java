package com.aipb.assistant.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A reply: why it stopped, its text, the tool calls it made and its usage. {@code raw} is the provider's own message
 * object, replayed as-is when the conversation continues.
 */
public record LlmReply(String stopReason, String text, List<ToolUse> toolUses, Usage usage, String refusalCategory,
                       Object raw) {

    public record ToolUse(String id, String name, JsonNode input) {}

    public record Usage(long input, long output, long cacheRead, long cacheWrite) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);

        public Usage plus(Usage o) {
            return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite);
        }
    }

    public boolean refused() { return "refusal".equals(stopReason); }

    public boolean truncated() { return "max_tokens".equals(stopReason); }
}
