package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantEvents;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.BuildGate;
import com.aipb.assistant.llm.LlmReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Stage 2 - get the right numbers. Objective: a read-only pipeline returning exactly the brief's figures, with
 * declared columns. Process: ground each measure in the data dictionary, confirm assumptions with
 * sample_documents / distinct_values, test with run_query, then submit_data. Sees: the primer, its rules, the data
 * overview and full dictionary, three worked examples (static); the page, the data's today and the brief - plus, on a
 * revision, the previous data and the change asked for, or a review's findings (dynamic). Gate: {@link BuildGate}.
 */
@Component
@RequiredArgsConstructor
public class BuildStage {

    private final StageRunner runner;
    private final ContextAssembler context;
    private final AssistantTools tools;
    private final BuildGate gate;

    /** What a revision starts from: the previous accepted data, and why it must change. */
    public record Revision(String previousData, String changeRequest, String reviewFindings) {}

    public ObjectNode run(JsonNode brief, Revision revision, AssistantTools.Context ctx, RunRecorder rec, Instant deadline,
                          AssistantEvents events) {
        List<ContextAssembler.Block> system = context.buildSystem();
        String user = userContext(brief, revision, ctx);
        rec.context(context.manifest(system, user), user);
        Handler handler = new Handler(brief, ctx);
        runner.toolLoop("build", system, tools.definitions("distinct_values", "run_query", "sample_documents", "submit_data"),
                user, "submit_data", handler, rec, deadline, events);
        return handler.artifact;
    }

    String userContext(JsonNode brief, Revision revision, AssistantTools.Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.page(ctx.layoutId(), ctx.pageId(), ctx.sampleParams(), false));
        sb.append(context.dataToday());
        sb.append("<brief>\n").append(brief.toPrettyString()).append("\n</brief>\n");
        if (revision != null && revision.previousData() != null) {
            sb.append("<previous_data>\nThe data built for the previous version of this widget:\n").append(withoutPreview(revision.previousData()))
                    .append("\n</previous_data>\n");
            if (revision.changeRequest() != null)
                sb.append("<change_request>\n").append(revision.changeRequest()).append("\n</change_request>\n");
        }
        if (revision != null && revision.reviewFindings() != null)
            sb.append("<review_findings>\nAn independent review found these problems with the previous data. Fix them.\n")
                    .append(revision.reviewFindings()).append("\n</review_findings>\n");
        sb.append("\nBuild the data for this brief, test it with run_query, and submit it with submit_data.");
        return sb.toString();
    }

    private static String withoutPreview(String data) {
        try {
            ObjectNode n = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree(data);
            n.remove(List.of("preview", "warnings", "sampleParams"));
            return n.toPrettyString();
        } catch (Exception e) {
            return data;
        }
    }

    /** Routes tool calls; submit_data goes through the gate and ends the loop when accepted. */
    private final class Handler implements StageRunner.ToolHandler {
        private final JsonNode brief;
        private final AssistantTools.Context ctx;
        private ObjectNode artifact;

        Handler(JsonNode brief, AssistantTools.Context ctx) {
            this.brief = brief;
            this.ctx = ctx;
        }

        @Override
        public AssistantTools.Outcome handle(LlmReply.ToolUse use) {
            JsonNode in = use.input() == null ? JsonNodeFactory.instance.objectNode() : use.input();
            return switch (use.name()) {
                case "run_query" -> tools.runQuery(in, ctx);
                case "sample_documents" -> tools.sampleDocuments(in, ctx);
                case "distinct_values" -> tools.distinctValues(in, ctx);
                case "submit_data" -> {
                    BuildGate.Result r = gate.check(in, ctx, brief.path("titleHint").asText(), brief.path("question").asText());
                    if (r.accepted()) {
                        artifact = r.artifact();
                        yield new AssistantTools.Outcome("{\"accepted\": true}", false, "The data passed the automatic checks");
                    }
                    yield new AssistantTools.Outcome("Not accepted yet. Fix these and submit again:\n- " + String.join("\n- ", r.errors()),
                            true, "The data needs fixes (" + r.errors().size() + " issue" + (r.errors().size() == 1 ? "" : "s") + ")");
                }
                default -> new AssistantTools.Outcome("{\"error\": \"unknown tool " + use.name() + "\"}", true, "Unknown tool " + use.name());
            };
        }

        @Override
        public boolean done() { return artifact != null; }
    }
}
