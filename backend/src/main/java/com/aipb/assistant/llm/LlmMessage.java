package com.aipb.assistant.llm;

import java.util.List;

/**
 * A conversation turn. User turns carry text and tool results. Assistant turns carry the model's reply exactly as
 * returned ({@code raw}) so it goes back verbatim - thinking blocks included, which must not be altered.
 */
public sealed interface LlmMessage {

    record User(List<Block> blocks) implements LlmMessage {
        public static User text(String text) { return new User(List.of(new Text(text))); }
    }

    record Assistant(LlmReply reply) implements LlmMessage {}

    sealed interface Block {}

    record Text(String text) implements Block {}

    record ToolResult(String toolUseId, String content, boolean isError) implements Block {}
}
