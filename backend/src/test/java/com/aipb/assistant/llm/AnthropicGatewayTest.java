package com.aipb.assistant.llm;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The request the SDK will send, checked offline: caching, thinking, effort, structured output, tools, fallbacks. */
class AnthropicGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void requestCarriesCachingThinkingEffortSchemaToolsAndFallbacks() throws Exception {
        AnthropicGateway gw = new AnthropicGateway("test-key", "https://api.anthropic.com");
        JsonNode toolSchema = mapper.readTree("""
                {"type":"object","properties":{"collection":{"type":"string"}},"required":["collection"],"additionalProperties":false}""");
        JsonNode outSchema = mapper.readTree("""
                {"type":"object","properties":{"outcome":{"type":"string"}},"required":["outcome"],"additionalProperties":false}""");
        LlmRequest req = new LlmRequest("intake", "claude-opus-5-5", "medium", 8000,
                List.of("primer", "stage rules"),
                List.of(new LlmRequest.ToolDef("sample_documents", "Look at documents.", toolSchema, true)),
                List.of(LlmMessage.User.text("hello")), outSchema);

        MessageCreateParams p = gw.params(req);
        JsonNode body = ObjectMappers.jsonMapper().valueToTree(p._body());

        assertThat(body.get("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.at("/thinking/type").asText()).isEqualTo("adaptive");
        assertThat(body.at("/output_config/effort").asText()).isEqualTo("medium");
        assertThat(body.at("/output_config/format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/output_config/format/schema/required/0").asText()).isEqualTo("outcome");
        // one breakpoint on the last system block (tools + system cached together), none on earlier blocks
        assertThat(body.at("/system/0/cache_control").isMissingNode()).isTrue();
        assertThat(body.at("/system/1/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(body.at("/cache_control/type").asText()).isEqualTo("ephemeral");
        assertThat(body.at("/tools/0/name").asText()).isEqualTo("sample_documents");
        assertThat(body.at("/tools/0/strict").asBoolean()).isTrue();
        assertThat(body.at("/tools/0/input_schema/additionalProperties").asBoolean(true)).isFalse();
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(p._additionalBodyProperties().get("fallbacks").convert(String.class)).isEqualTo("default");
        assertThat(p._additionalHeaders().values("anthropic-beta")).contains("server-side-fallback-2026-07-01");
        assertThat(body.has("tool_choice")).isFalse(); // forced tool use is rejected on Opus 5.5 - never sent
    }

    @Test
    void noFallbacksThroughOtherBaseUrls() {
        AnthropicGateway gw = new AnthropicGateway("k", "https://proxy.internal");
        MessageCreateParams p = gw.params(new LlmRequest("x", "claude-opus-5-5", null, 100, List.of("s"), List.of(),
                List.of(LlmMessage.User.text("hi")), null));
        assertThat(p._additionalBodyProperties()).doesNotContainKey("fallbacks");
    }
}
