package com.aipb.assistant.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Claude through the official Java SDK. Every request: adaptive thinking (always on for Claude 5 models - effort is
 * the control), the stage's effort, a cache breakpoint on the last system block plus automatic caching of the
 * conversation tail, and server-side refusal fallbacks. Assistant turns are replayed with addMessage(Message) - the
 * exact object the API returned - so thinking blocks go back unaltered (the conversation stays append-only).
 */
@Slf4j
@Component
public class AnthropicGateway implements LlmGateway {

    /** Models that accept fallbacks: "default" (server-side refusal fallback). */
    private static final Set<String> FALLBACK_MODELS = Set.of("claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5", "claude-fable-5-1");

    private final String apiKey;
    private final String baseUrl;
    private volatile AnthropicClient client;

    public AnthropicGateway(@Value("${aipb.assistant.api-key:}") String apiKey,
                            @Value("${aipb.assistant.base-url:https://api.anthropic.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public LlmReply send(LlmRequest req) {
        if (!enabled()) throw new LlmException("Claude is not configured (set ANTHROPIC_API_KEY)");
        MessageCreateParams params = params(req);
        Message msg;
        try {
            msg = client().messages().create(params);
        } catch (AnthropicServiceException e) {
            log.warn("Claude call failed for stage {}: {} {}", req.stage(), e.statusCode(), e.getMessage());
            throw new LlmException("Claude returned an error (" + e.statusCode() + "): " + e.getMessage(), e);
        } catch (AnthropicException e) {
            log.warn("Claude call failed for stage {}: {}", req.stage(), e.getMessage());
            throw new LlmException("Claude could not be reached: " + e.getMessage(), e);
        }
        return reply(msg);
    }

    /** The request exactly as it will be sent (package-private so tests can inspect it without calling the API). */
    MessageCreateParams params(LlmRequest req) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(req.model())
                .maxTokens(req.maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .cacheControl(CacheControlEphemeral.builder().build());

        List<TextBlockParam> system = new ArrayList<>();
        for (int i = 0; i < req.system().size(); i++) {
            TextBlockParam.Builder t = TextBlockParam.builder().text(req.system().get(i));
            if (i == req.system().size() - 1) t.cacheControl(CacheControlEphemeral.builder().build());
            system.add(t.build());
        }
        b.systemOfTextBlockParams(system);

        for (LlmRequest.ToolDef t : req.tools()) b.addTool(tool(t));

        OutputConfig.Builder oc = OutputConfig.builder();
        if (req.effort() != null && !req.effort().isBlank()) oc.effort(OutputConfig.Effort.of(req.effort()));
        if (req.outputSchema() != null) oc.format(JsonOutputFormat.builder().schema(schema(req.outputSchema())).build());
        b.outputConfig(oc.build());

        for (LlmMessage m : req.messages()) {
            if (m instanceof LlmMessage.Assistant a) b.addMessage((Message) a.reply().raw());
            else if (m instanceof LlmMessage.User u) b.addMessage(user(u));
        }

        if (FALLBACK_MODELS.contains(req.model()) && baseUrl.contains("api.anthropic.com")) {
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
        }
        return b.build();
    }

    // ------------------------------------------------------------------ mapping
    private Tool tool(LlmRequest.ToolDef t) {
        JsonNode s = t.inputSchema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        s.path("properties").fields().forEachRemaining(e -> props.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(props.build());
        List<String> required = new ArrayList<>();
        s.path("required").forEach(r -> required.add(r.asText()));
        if (!required.isEmpty()) schema.required(required);
        if (s.has("additionalProperties")) schema.putAdditionalProperty("additionalProperties", JsonValue.fromJsonNode(s.get("additionalProperties")));
        Tool.Builder tb = Tool.builder().name(t.name()).description(t.description()).inputSchema(schema.build());
        if (t.strict()) tb.strict(true);
        return tb.build();
    }

    private static JsonOutputFormat.Schema schema(JsonNode s) {
        JsonOutputFormat.Schema.Builder b = JsonOutputFormat.Schema.builder();
        s.fields().forEachRemaining(e -> b.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
        return b.build();
    }

    private static MessageParam user(LlmMessage.User u) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (LlmMessage.Block blk : u.blocks()) {
            if (blk instanceof LlmMessage.Text t) blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(t.text()).build()));
            else if (blk instanceof LlmMessage.ToolResult r) blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(r.toolUseId()).content(r.content()).isError(r.isError()).build()));
        }
        return MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build();
    }

    private LlmReply reply(Message msg) {
        StringBuilder text = new StringBuilder();
        List<LlmReply.ToolUse> tools = new ArrayList<>();
        for (ContentBlock block : msg.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(t -> tools.add(new LlmReply.ToolUse(t.id(), t.name(), t._input().convert(JsonNode.class))));
        }
        Usage u = msg.usage();
        LlmReply.Usage usage = new LlmReply.Usage(u.inputTokens(), u.outputTokens(),
                u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L));
        String stop = msg.stopReason().map(StopReason::asString).orElse("end_turn");
        String category = msg.stopDetails().flatMap(RefusalStopDetails::category).map(RefusalStopDetails.Category::asString).orElse(null);
        return new LlmReply(stop, text.toString(), tools, usage, category, msg);
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl)
                            .timeout(Duration.ofMinutes(5)).maxRetries(2).build();
                }
                c = client;
            }
        }
        return c;
    }
}
