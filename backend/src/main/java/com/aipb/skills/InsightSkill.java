package com.aipb.skills;

import com.aipb.assistant.ProposalBuilder;
import com.aipb.assistant.stages.BuildStage;
import com.aipb.assistant.stages.NarrateStage;
import com.aipb.assistant.stages.PresentStage;
import com.aipb.assistant.stages.ReviewStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The insight skill: a few sentences about figures (a summary, a forecast), authored once and rendered by code on
 * every view - no model call when it is shown. The data is built as for a widget; NARRATE writes the statements of the
 * narrative primitive (see com.aipb.widget.NarrativeRenderer).
 */
@Component
public class InsightSkill extends DataSkill {

    public static final String ID = "insight";
    private static final Map<String, String> LABELS = labels("narrate", "Writing the insight");

    private final NarrateStage narrate;

    public InsightSkill(BuildStage build, ReviewStage review, NarrateStage narrate, ProposalBuilder proposals, ObjectMapper mapper) {
        super(build, review, proposals, mapper);
        this.narrate = narrate;
    }

    @Override
    public String id() { return ID; }

    @Override
    public Map<String, String> stageLabels() { return LABELS; }

    @Override
    protected PresentStage.Result design(SkillRun run, JsonNode brief, JsonNode data, String changeRequest, StageOps ops) {
        return ops.stage("narrate", rec -> narrate.run(brief, data, changeRequest, run.ctx(), run.template(), rec, ops.deadline()));
    }
}
