package com.aipb.skills;

import com.aipb.assistant.AssistantSession;
import com.aipb.assistant.AssistantTools;
import com.aipb.assistant.ProposalBuilder;
import com.aipb.assistant.stages.BuildStage;
import com.aipb.assistant.stages.PresentStage;
import com.aipb.assistant.stages.ReviewStage;
import com.aipb.widget.WidgetDefinition;
import com.aipb.widget.WidgetDefinitionRepository;
import com.aipb.widget.WidgetLibrary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The widget skill: a declarative spec that shows data.
 * <pre>
 * proceed -> BUILD -> PRESENT -> proposal
 * verify (opt-in) -> REVIEW -> [findings -> BUILD -> PRESENT]
 * </pre>
 * A revision that only changes the display re-runs PRESENT alone.
 */
@Component
@RequiredArgsConstructor
public class WidgetSkill implements SkillKind {

    private static final Map<String, String> LABELS = labels();

    private final BuildStage build;
    private final ReviewStage review;
    private final PresentStage present;
    private final ProposalBuilder proposals;
    private final WidgetLibrary library;
    private final WidgetDefinitionRepository definitions;
    private final ObjectMapper mapper;

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("build", "Finding the data");
        m.put("review", "Checking the numbers");
        m.put("present", "Designing the widget");
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String id() { return WidgetDefinition.WIDGET; }

    @Override
    public Map<String, String> stageLabels() { return LABELS; }

    @Override
    public void proceed(SkillRun run, JsonNode out, String text, StageOps ops) {
        AssistantSession s = run.session();
        JsonNode brief = out.path("brief");
        List<String> changed = new ArrayList<>();
        out.path("changed").forEach(c -> changed.add(c.asText()));
        boolean presentationOnly = s.getSpec() != null && s.getData() != null && !changed.isEmpty()
                && changed.stream().allMatch("presentation"::equals);
        s.setBrief(brief.toString());
        ops.events().emit("message", Map.of("text", out.path("message").asText(), "kind", "ack"));
        AssistantTools.Context ctx = run.ctx();

        JsonNode data;
        if (presentationOnly) {
            data = read(s.getData());
        } else {
            BuildStage.Revision rev = s.getData() == null ? null : new BuildStage.Revision(s.getData(), text, null);
            data = ops.stage("build", rec -> build.run(brief, rev, ctx, run.template(), rec, ops.deadline(), ops.events()));
            s.setData(data.toString());
            s.setReviewState("none");
            s.setReview(null);
        }
        presentAndPropose(run, brief, data, presentationOnly ? text : null, ops, false);
    }

    @Override
    public void verify(SkillRun run, StageOps ops) {
        AssistantSession s = run.session();
        if (s.getSpec() == null || s.getData() == null) {
            ops.reply("There is no proposed widget to verify yet.", "answer");
            return;
        }
        JsonNode brief = read(s.getBrief());
        JsonNode data = read(s.getData());
        AssistantTools.Context ctx = run.ctx();
        ReviewStage.Result r = ops.stage("review", rec -> review.run(brief, data, ctx, run.template(), rec, ops.deadline(), ops.events()));
        s.setReview(r.review().toString());
        if (r.passed()) {
            s.setReviewState("passed");
            ObjectNode p = (ObjectNode) read(s.getProposal());
            ObjectNode rv = p.putObject("review");
            rv.put("state", "passed");
            rv.put("summary", r.review().path("summary").asText());
            rv.set("checks", r.review().path("checks"));
            rv.set("independentChecks", r.review().path("independentChecks"));
            rv.set("issues", r.review().path("issues"));
            ops.propose(p, "The review confirmed the numbers: " + r.review().path("summary").asText());
            return;
        }
        // the review found problems: rebuild with its findings, redesign, and flag the result as not verified
        s.setReviewState("failed");
        String findings = r.review().path("issues").toPrettyString();
        JsonNode rebuilt = ops.stage("build", rec -> build.run(brief, new BuildStage.Revision(s.getData(), null, findings), ctx, run.template(),
                rec, ops.deadline(), ops.events()));
        s.setData(rebuilt.toString());
        presentAndPropose(run, brief, rebuilt, null, ops, true);
    }

    private void presentAndPropose(SkillRun run, JsonNode brief, JsonNode data, String changeRequest, StageOps ops, boolean revisedAfterReview) {
        AssistantSession s = run.session();
        PresentStage.Result pr = ops.stage("present", rec -> present.run(brief, data, changeRequest, run.ctx(), run.template(), rec, ops.deadline()));
        s.setView(pr.design().toString());
        s.setSpec(pr.checked().spec().toString());
        JsonNode reviewed = s.getReview() == null ? null : read(s.getReview());
        ObjectNode p = proposals.newWidget(brief, data, pr.checked().spec(), pr.checked().report(), s.getReviewState(), reviewed, revisedAfterReview);
        String text = (revisedAfterReview ? "The review found problems, so I revised the data (not re-reviewed). " : "")
                + "Here's a proposal: \"" + p.path("title").asText() + "\" - " + p.path("description").asText();
        ops.propose(p, text);
    }

    @Override
    public ObjectNode reuseProposal(WidgetDefinition d, Map<String, String> params) { return proposals.reuse(d, params); }

    @Override
    public String save(SkillRun run) {
        AssistantSession s = run.session();
        SkillTemplate t = run.template();
        ObjectNode spec = (ObjectNode) read(s.getSpec());
        spec.put("id", uniqueId(spec.path("id").asText()));
        ObjectNode prov = spec.putObject("provenance");
        prov.put("sessionId", s.getId());
        JsonNode brief = s.getBrief() == null ? null : read(s.getBrief());
        if (brief != null) prov.put("question", brief.path("question").asText());
        prov.put("reviewed", s.getReviewState());
        prov.put("template", t.id() + "@v" + t.version());
        return library.create(spec, "assistant", s.getOwner(), WidgetDefinition.PERSONAL, s.getLayoutId(), s.getPageId(), s.getParams(),
                new WidgetDefinition.Origin(id(), t.id(), t.version())).definition().getId();
    }

    private String uniqueId(String base) {
        String id = base;
        for (int n = 2; definitions.existsById(id); n++) id = base + "-" + n;
        return id;
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("stored assistant state is not valid JSON", e);
        }
    }
}
