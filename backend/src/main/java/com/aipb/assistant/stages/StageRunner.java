package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantEvents;
import com.aipb.assistant.AssistantProperties;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The two ways a stage calls Claude, with the same rules everywhere:
 * <ul>
 *   <li>{@link #structured}: one answer constrained to a JSON schema, checked by a gate; if the gate rejects it, the
 *   reasons are appended and the model corrects itself (bounded attempts).</li>
 *   <li>{@link #toolLoop}: the model explores with tools and finishes by calling a submit tool, whose gate either
 *   accepts the result or returns the reasons (bounded tool calls and submissions).</li>
 * </ul>
 * Conversations are append-only: assistant turns go back exactly as received, results and corrections are appended.
 * A refusal stops the stage; a reply cut off by max_tokens is retried once with twice the room; every call checks
 * the run's deadline.
 */
@Component
@RequiredArgsConstructor
public class StageRunner {

    private final LlmGateway gateway;
    private final AssistantProperties props;
    private final ObjectMapper mapper;

    /** Handles the stage's tool calls. Returning with {@link #done()} true ends the loop (the submission was accepted). */
    public interface ToolHandler {
        AssistantTools.Outcome handle(LlmReply.ToolUse use);
        boolean done();
    }

    public JsonNode structured(String stage, List<ContextAssembler.Block> system, String userContext, JsonNode schema,
                               Function<JsonNode, List<String>> gate, int maxAttempts, RunRecorder rec, Instant deadline) {
        List<LlmMessage> messages = new ArrayList<>(List.of(LlmMessage.User.text(userContext)));
        for (int attempt = 1; ; attempt++) {
            LlmReply reply = call(stage, system, List.of(), messages, schema, rec, deadline);
            List<String> errors;
            JsonNode value = null;
            try {
                value = mapper.readTree(reply.text());
                errors = value == null || !value.isObject() ? List.of("the reply must be one JSON object") : gate.apply(value);
            } catch (Exception e) {
                errors = List.of("the reply was not valid JSON: " + e.getMessage());
            }
            if (errors.isEmpty()) return value;
            if (attempt >= maxAttempts)
                throw new StageFailure("The " + stage + " step could not produce a usable result: " + String.join("; ", errors));
            messages.add(new LlmMessage.Assistant(reply));
            messages.add(LlmMessage.User.text("That result can't be used yet:\n- " + String.join("\n- ", errors) + "\nReturn a corrected result."));
        }
    }

    public void toolLoop(String stage, List<ContextAssembler.Block> system, List<LlmRequest.ToolDef> tools, String userContext,
                         String submitTool, ToolHandler handler, RunRecorder rec, Instant deadline, AssistantEvents events) {
        List<LlmMessage> messages = new ArrayList<>(List.of(LlmMessage.User.text(userContext)));
        int toolCalls = 0;
        int submits = 0;
        boolean nudged = false;
        while (true) {
            LlmReply reply = call(stage, system, tools, messages, null, rec, deadline);
            messages.add(new LlmMessage.Assistant(reply));
            if (reply.toolUses().isEmpty()) {
                if (nudged) throw new StageFailure("The " + stage + " step stopped without submitting a result.");
                nudged = true;
                messages.add(LlmMessage.User.text("Finish by calling " + submitTool + " with your result."));
                continue;
            }
            List<LlmMessage.Block> results = new ArrayList<>();
            for (LlmReply.ToolUse use : reply.toolUses()) {
                if (++toolCalls > props.getMaxToolCalls())
                    throw new StageFailure("The " + stage + " step used its " + props.getMaxToolCalls() + " tool calls without finishing.");
                if (use.name().equals(submitTool) && ++submits > props.getMaxSubmits())
                    throw new StageFailure("The " + stage + " step's submissions kept failing the automatic checks.");
                long t0 = System.currentTimeMillis();
                AssistantTools.Outcome out = handler.handle(use);
                rec.tool(use.name(), !out.isError(), System.currentTimeMillis() - t0, out.summary());
                events.emit("activity", Map.of("stage", stage, "text", out.summary(), "ok", !out.isError()));
                results.add(new LlmMessage.ToolResult(use.id(), out.content(), out.isError()));
                if (handler.done()) return;
            }
            messages.add(new LlmMessage.User(results));
        }
    }

    private LlmReply call(String stage, List<ContextAssembler.Block> system, List<LlmRequest.ToolDef> tools,
                          List<LlmMessage> messages, JsonNode schema, RunRecorder rec, Instant deadline) {
        if (Instant.now().isAfter(deadline)) throw new StageFailure("This took too long and was stopped; please try again.");
        LlmRequest req = new LlmRequest(stage, props.getModel(), props.effort(stage), props.maxTokens(stage),
                ContextAssembler.texts(system), tools, List.copyOf(messages), schema);
        LlmReply reply = gateway.send(req);
        rec.call(reply.usage());
        if (reply.truncated()) {
            reply = gateway.send(req.withMaxTokens(req.maxTokens() * 2));
            rec.call(reply.usage());
        }
        if (reply.refused())
            throw new StageFailure("Claude declined this request" + (reply.refusalCategory() == null ? "." : " (" + reply.refusalCategory() + ")."));
        if (reply.truncated()) throw new StageFailure("The " + stage + " step's answer was too long to complete.");
        return reply;
    }
}
