package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantEvents;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.BuildGate;
import com.aipb.assistant.gates.TemplateGate;
import com.aipb.assistant.llm.LlmReply;
import com.aipb.skills.SkillTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Stage 2 - get the right numbers. Objective: a read-only Cypher query returning exactly the brief's figures, with
 * declared columns. Process: ground each measure in the data dictionary, confirm assumptions with
 * sample_nodes / distinct_values / graph_schema, test with run_query, then submit_data. Sees: the primer, its rules, the data
 * overview and full dictionary, three worked examples (or the template's), the template's guidance (static); the page,
 * the data's today and the brief - plus, on a revision, the previous data and the change asked for, or a review's
 * findings (dynamic). Gate: the template's source and row limits ({@link TemplateGate}), then {@link BuildGate}.
 */
@Component
@RequiredArgsConstructor
public class BuildStage {

    private final StageRunner runner;
    private final ContextAssembler context;
    private final AssistantTools tools;
    private final BuildGate gate;
    private final TemplateGate templateGate;

    /** What a revision starts from: the previous accepted data, and why it must change. */
    public record Revision(String previousData, String changeRequest, String reviewFindings) {}

    public ObjectNode run(JsonNode brief, Revision revision, AssistantTools.Context ctx, SkillTemplate template, RunRecorder rec,
                          Instant deadline, AssistantEvents events) {
        List<ContextAssembler.Block> system = context.buildSystem(template);
        String user = userContext(brief, revision, ctx);
        rec.context(context.manifest(system, user), user);
        Handler handler = new Handler(brief, ctx, template);
        runner.toolLoop("build", system, tools.definitions("distinct_values", "graph_schema", "run_query", "sample_nodes", "submit_data"),
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
        private final SkillTemplate template;
        private ObjectNode artifact;

        Handler(JsonNode brief, AssistantTools.Context ctx, SkillTemplate template) {
            this.brief = brief;
            this.ctx = ctx;
            this.template = template;
        }

        @Override
        public AssistantTools.Outcome handle(LlmReply.ToolUse use) {
            JsonNode in = use.input() == null ? JsonNodeFactory.instance.objectNode() : use.input();
            return switch (use.name()) {
                case "run_query" -> tools.runQuery(in, ctx);
                case "sample_nodes" -> tools.sampleNodes(in, ctx);
                case "graph_schema" -> tools.graphSchema(in, ctx);
                case "distinct_values" -> tools.distinctValues(in, ctx);
                case "submit_data" -> {
                    List<String> errors = template == null ? List.of() : templateGate.build(in, template);
                    if (errors.isEmpty()) {
                        BuildGate.Result r = gate.check(in, ctx, brief.path("titleHint").asText(), brief.path("question").asText());
                        errors = r.errors();
                        if (r.accepted() && template != null) errors = templateGate.rows(r.artifact(), template);
                        if (errors.isEmpty()) {
                            artifact = r.artifact();
                            yield new AssistantTools.Outcome("{\"accepted\": true}", false, "The data passed the automatic checks");
                        }
                    }
                    yield new AssistantTools.Outcome("Not accepted yet. Fix these and submit again:\n- " + String.join("\n- ", errors),
                            true, "The data needs fixes (" + errors.size() + " issue" + (errors.size() == 1 ? "" : "s") + ")");
                }
                default -> new AssistantTools.Outcome("{\"error\": \"unknown tool " + use.name() + "\"}", true, "Unknown tool " + use.name());
            };
        }

        @Override
        public boolean done() { return artifact != null; }
    }
}
