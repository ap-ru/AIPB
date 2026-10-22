package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantProperties;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.PresentGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/**
 * Stage 4 - design the display. Objective: the clearest display of these rows on this page. Sees: the primer, its
 * rules, the design catalog (primitives, roles, row limits, guidance) and four worked examples (static); the page,
 * the brief, the data's columns, sample rows and any warnings (dynamic). Gate: {@link PresentGate} - the assembled
 * spec must pass the widget validator for the page.
 */
@Component
public class PresentStage {

    static final int SAMPLE_ROWS = 10;

    private final StageRunner runner;
    private final ContextAssembler context;
    private final PresentGate gate;
    private final AssistantProperties props;
    private final ObjectMapper mapper;
    private final JsonNode schema;

    public PresentStage(StageRunner runner, ContextAssembler context, PresentGate gate, AssistantProperties props, ObjectMapper mapper) {
        this.runner = runner;
        this.context = context;
        this.gate = gate;
        this.props = props;
        this.mapper = mapper;
        try (InputStream in = new ClassPathResource("assistant/schemas/present.json").getInputStream()) {
            this.schema = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The design and the assembled, validated spec (with its validation report). */
    public record Result(JsonNode design, PresentGate.Result checked) {}

    public Result run(JsonNode brief, JsonNode artifact, String changeRequest, AssistantTools.Context ctx, RunRecorder rec, Instant deadline) {
        List<ContextAssembler.Block> system = context.presentSystem();
        String user = userContext(brief, artifact, changeRequest, ctx);
        rec.context(context.manifest(system, user), user);
        PresentGate.Result[] last = new PresentGate.Result[1];
        JsonNode design = runner.structured("present", system, user, schema, d -> {
            last[0] = gate.check(d, artifact, ctx);
            return last[0].errors();
        }, props.getMaxPresentAttempts(), rec, deadline);
        return new Result(design, last[0]);
    }

    String userContext(JsonNode brief, JsonNode artifact, String changeRequest, AssistantTools.Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.page(ctx.layoutId(), ctx.pageId(), ctx.sampleParams()));
        ObjectNode b = mapper.createObjectNode();
        for (String k : List.of("question", "titleHint", "scope", "time", "display", "measures")) b.set(k, brief.path(k));
        sb.append("<brief>\n").append(b.toPrettyString()).append("\n</brief>\n");
        sb.append("<data>\ncolumns:\n");
        artifact.path("columns").forEach(c -> {
            String type = c.path("type").asText();
            String format = c.path("format").asText(type.equals("number") ? "number" : type.equals("date") ? "date" : "text");
            sb.append("- ").append(c.path("name").asText()).append(" (").append(type).append(", unit ").append(c.path("unit").asText("none"))
                    .append(", format ").append(format).append("): ").append(c.path("label").asText()).append('\n');
        });
        JsonNode preview = artifact.path("preview");
        ArrayNode rows = mapper.createArrayNode();
        for (int i = 0; i < Math.min(SAMPLE_ROWS, preview.path("rows").size()); i++) rows.add(preview.path("rows").get(i));
        sb.append("row count for the sample: ").append(preview.path("count").asInt()).append('\n');
        sb.append("sample rows").append(preview.path("count").asInt() > SAMPLE_ROWS ? " (first " + SAMPLE_ROWS + ")" : "").append(":\n")
                .append(rows.toPrettyString()).append('\n');
        if (artifact.path("warnings").size() > 0) sb.append("warnings: ").append(artifact.path("warnings")).append('\n');
        sb.append("</data>\n");
        if (changeRequest != null) sb.append("<change_request>\n").append(changeRequest).append("\n</change_request>\n");
        sb.append("\nDesign the display for this data.");
        return sb.toString();
    }
}
