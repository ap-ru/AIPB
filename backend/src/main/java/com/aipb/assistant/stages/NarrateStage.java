package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantProperties;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.PresentGate;
import com.aipb.skills.SkillTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;

/**
 * An insight's design step - write the statements. Objective: the few sentences a banker would say about these figures,
 * as templates code fills on every view. Sees: the primer, its rules, the narrative primitive and the formats a slot can
 * ask for, and the template's guidance (static); the page, the brief, the data's columns and sample rows (dynamic, as
 * PRESENT sees them). Gate: {@link PresentGate#checkNarrative} - the assembled spec must pass the widget validator,
 * which checks every slot and condition and renders the statements on the sample.
 */
@Component
public class NarrateStage {

    private final StageRunner runner;
    private final ContextAssembler context;
    private final PresentStage present;
    private final PresentGate gate;
    private final AssistantProperties props;
    private final JsonNode schema;

    public NarrateStage(StageRunner runner, ContextAssembler context, PresentStage present, PresentGate gate, AssistantProperties props,
                        ObjectMapper mapper) {
        this.runner = runner;
        this.context = context;
        this.present = present;
        this.gate = gate;
        this.props = props;
        try (InputStream in = new ClassPathResource("assistant/schemas/narrate.json").getInputStream()) {
            this.schema = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public PresentStage.Result run(JsonNode brief, JsonNode artifact, String changeRequest, AssistantTools.Context ctx, SkillTemplate template,
                                   RunRecorder rec, Instant deadline) {
        List<ContextAssembler.Block> system = context.narrateSystem(template);
        String user = present.dataContext(brief, artifact, changeRequest, ctx) + "\nWrite the insight for this data.";
        rec.context(context.manifest(system, user), user);
        PresentGate.Result[] last = new PresentGate.Result[1];
        JsonNode design = runner.structured("narrate", system, user, schema, d -> {
            last[0] = gate.checkNarrative(d, artifact, ctx);
            return last[0].errors();
        }, props.getMaxPresentAttempts(), rec, deadline);
        return new PresentStage.Result(design, last[0]);
    }
}
