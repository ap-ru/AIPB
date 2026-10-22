package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantEvents;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.ReviewGate;
import com.aipb.assistant.llm.LlmReply;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Stage 3 (opt-in) - independent verification, run only when the banker asks. Objective: decide whether the pipeline
 * computes what the brief asks and prove a key figure a second way. Sees: the primer, its rules, the data overview and
 * dictionary (static); the page, the data's today, the brief and the built data with its sample output - never the
 * build conversation (dynamic). Gate: {@link ReviewGate}.
 */
@Component
@RequiredArgsConstructor
public class ReviewStage {

    private final StageRunner runner;
    private final ContextAssembler context;
    private final AssistantTools tools;
    private final ReviewGate gate;

    /** The submitted review and whether it passed. */
    public record Result(JsonNode review, boolean passed) {}

    public Result run(JsonNode brief, JsonNode artifact, AssistantTools.Context ctx, RunRecorder rec, Instant deadline, AssistantEvents events) {
        List<ContextAssembler.Block> system = context.reviewSystem();
        String user = userContext(brief, artifact, ctx);
        rec.context(context.manifest(system, user), user);
        JsonNode[] review = new JsonNode[1];
        boolean[] passed = new boolean[1];
        runner.toolLoop("review", system, tools.definitions("distinct_values", "run_query", "sample_documents", "submit_review"),
                user, "submit_review", new StageRunner.ToolHandler() {
                    @Override
                    public AssistantTools.Outcome handle(LlmReply.ToolUse use) {
                        JsonNode in = use.input() == null ? JsonNodeFactory.instance.objectNode() : use.input();
                        return switch (use.name()) {
                            case "run_query" -> tools.runQuery(in, ctx);
                            case "sample_documents" -> tools.sampleDocuments(in, ctx);
                            case "distinct_values" -> tools.distinctValues(in, ctx);
                            case "submit_review" -> {
                                ReviewGate.Result r = gate.check(in);
                                if (r.accepted()) {
                                    review[0] = in;
                                    passed[0] = r.passed();
                                    yield new AssistantTools.Outcome("{\"accepted\": true}", false, r.passed() ? "Review passed" : "Review found problems");
                                }
                                yield new AssistantTools.Outcome("Not accepted:\n- " + String.join("\n- ", r.errors()), true, "The review needs more evidence");
                            }
                            default -> new AssistantTools.Outcome("{\"error\": \"unknown tool\"}", true, "Unknown tool " + use.name());
                        };
                    }

                    @Override
                    public boolean done() { return review[0] != null; }
                }, rec, deadline, events);
        return new Result(review[0], passed[0]);
    }

    String userContext(JsonNode brief, JsonNode artifact, AssistantTools.Context ctx) {
        ObjectNode data = artifact.deepCopy();
        JsonNode preview = data.remove("preview");
        data.remove(List.of("warnings", "sampleParams"));
        StringBuilder sb = new StringBuilder();
        sb.append(context.page(ctx.layoutId(), ctx.pageId(), ctx.sampleParams(), false));
        sb.append(context.dataToday());
        sb.append("<brief>\n").append(brief.toPrettyString()).append("\n</brief>\n");
        sb.append("<data_definition>\n").append(data.toPrettyString()).append("\n</data_definition>\n");
        if (preview != null) sb.append("<sample_output>\nrow count: ").append(preview.path("count").asInt()).append("\nrows: ")
                .append(preview.path("rows").toPrettyString()).append("\n</sample_output>\n");
        sb.append("\nVerify this widget's numbers, then submit your verdict with submit_review.");
        return sb.toString();
    }
}
