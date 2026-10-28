package com.aipb.skills;

import com.aipb.assistant.ProposalBuilder;
import com.aipb.assistant.stages.BuildStage;
import com.aipb.assistant.stages.PresentStage;
import com.aipb.assistant.stages.ReviewStage;
import com.aipb.widget.WidgetDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** The widget skill: a declarative spec that shows data with one design-system primitive (BUILD -> PRESENT). */
@Component
public class WidgetSkill extends DataSkill {

    private static final Map<String, String> LABELS = labels("present", "Designing the widget");

    private final PresentStage present;

    public WidgetSkill(BuildStage build, ReviewStage review, PresentStage present, ProposalBuilder proposals, ObjectMapper mapper) {
        super(build, review, proposals, mapper);
        this.present = present;
    }

    @Override
    public String id() { return WidgetDefinition.WIDGET; }

    @Override
    public Map<String, String> stageLabels() { return LABELS; }

    @Override
    protected PresentStage.Result design(SkillRun run, JsonNode brief, JsonNode data, String changeRequest, StageOps ops) {
        return ops.stage("present", rec -> present.run(brief, data, changeRequest, run.ctx(), run.template(), rec, ops.deadline()));
    }
}
