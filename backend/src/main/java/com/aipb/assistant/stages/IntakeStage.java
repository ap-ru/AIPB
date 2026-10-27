package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantSession;
import com.aipb.assistant.ContextAssembler;
import com.aipb.assistant.gates.TemplateGate;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.skills.SkillTemplate;
import com.aipb.widget.WidgetDefinition;
import com.aipb.widget.WidgetDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 - understand the request. Objective: one outcome for the banker's latest message (proceed with a brief,
 * clarify, answer, reuse, decline, verify). Sees: the primer, its rules, the data overview and index, the primitive
 * menu, and the template's guidance (static); the page, the data's today, the library, the Studio inputs, the current
 * brief and the conversation (dynamic). Gate: a reuse names a real library widget, a brief has a measure and a scope
 * the page supports and keeps to the template's constraints, a verify has something to verify, and every reply
 * carries a message.
 */
@Component
public class IntakeStage {

    static final int MAX_TURNS_IN_CONTEXT = 12;

    private final StageRunner runner;
    private final ContextAssembler context;
    private final WidgetDefinitionRepository library;
    private final LayoutService layouts;
    private final TemplateGate templateGate;
    private final JsonNode schema;

    public IntakeStage(StageRunner runner, ContextAssembler context, WidgetDefinitionRepository library, LayoutService layouts,
                       TemplateGate templateGate, ObjectMapper mapper) {
        this.runner = runner;
        this.context = context;
        this.library = library;
        this.layouts = layouts;
        this.templateGate = templateGate;
        try (InputStream in = new ClassPathResource("assistant/schemas/intake.json").getInputStream()) {
            this.schema = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The outcome for the banker's message; a proceed brief comes back with the template's defaults applied. */
    public JsonNode run(AssistantSession s, String bankerMessage, SkillTemplate template, RunRecorder rec, Instant deadline) {
        return run(s, bankerMessage, template, List.of(), rec, deadline);
    }

    /**
     * As above; {@code others} are the templates a request outside {@code template} may be redirected to (empty: no
     * redirects - a Custom widget run).
     */
    public JsonNode run(AssistantSession s, String bankerMessage, SkillTemplate template, List<SkillTemplate> others, RunRecorder rec,
                        Instant deadline) {
        List<ContextAssembler.Block> system = context.intakeSystem(template);
        String user = userContext(s, bankerMessage, template, others);
        rec.context(context.manifest(system, user), user);
        JsonNode out = runner.structured("intake", system, user, schema, o -> gate(o, s, template, others), 2, rec, deadline);
        if (out.path("brief").isObject() && out instanceof ObjectNode o)
            o.set("brief", templateGate.withDefaults(out.path("brief"), template));
        return out;
    }

    String userContext(AssistantSession s, String bankerMessage) { return userContext(s, bankerMessage, null, List.of()); }

    String userContext(AssistantSession s, String bankerMessage, SkillTemplate template, List<SkillTemplate> others) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.page(s.getLayoutId(), s.getPageId(), s.getParams()));
        sb.append(context.dataToday());
        sb.append(context.library(s.getOwner(), template == null ? WidgetDefinition.WIDGET : template.kind()));
        if (template != null && s.getInputs() != null && !s.getInputs().isEmpty()) {
            sb.append("<skill_inputs>\nWhat the banker chose in the Skills Studio for ").append(template.title()).append(":\n");
            for (SkillTemplate.Input in : template.inputs()) {
                String v = s.getInputs().get(in.name());
                if (v != null) sb.append("- ").append(in.label()).append(": ").append(v).append('\n');
            }
            sb.append("</skill_inputs>\n");
        }
        if (!others.isEmpty()) {
            sb.append("<other_templates>\n");
            others.forEach(o -> sb.append("- ").append(o.id()).append(": ").append(o.title()).append(" (").append(o.kind()).append(") - ")
                    .append(o.summary()).append('\n'));
            sb.append("</other_templates>\n");
        }
        if (s.getBrief() != null) {
            sb.append("<current_widget>\nThe banker is working on this widget (a revision changes it; a new subject is a new widget).\nbrief: ")
                    .append(s.getBrief()).append('\n');
            if (s.getView() != null) sb.append("display: ").append(s.getView()).append('\n');
            sb.append("review: ").append(s.getReviewState()).append("\n</current_widget>\n");
        }
        // the turns before the latest message (which is the last turn)
        List<AssistantSession.Turn> turns = s.getTurns();
        int end = turns.size() - 1;
        int from = Math.max(0, end - MAX_TURNS_IN_CONTEXT);
        if (end > from) {
            sb.append("<conversation>\n");
            for (int i = from; i < end; i++) {
                AssistantSession.Turn t = turns.get(i);
                sb.append(t.getRole().equals("user") ? "Banker: " : "Assistant: ").append(t.getText()).append('\n');
            }
            sb.append("</conversation>\n");
        }
        sb.append("<banker_message>\n").append(bankerMessage).append("\n</banker_message>\n\n");
        sb.append("Decide the outcome for the banker's message.");
        return sb.toString();
    }

    List<String> gate(JsonNode out, AssistantSession s) { return gate(out, s, null, List.of()); }

    List<String> gate(JsonNode out, AssistantSession s, SkillTemplate template, List<SkillTemplate> others) {
        List<String> errors = new ArrayList<>();
        String outcome = out.path("outcome").asText();
        if (out.path("message").asText("").isBlank()) errors.add("message is required for every outcome");
        switch (outcome) {
            case "reuse" -> {
                String id = out.path("reuseDefinitionId").asText("");
                String kind = template == null ? WidgetDefinition.WIDGET : template.kind();
                if (!library.findById(id).map(d -> d.visibleTo(s.getOwner()) && d.kind().equals(kind)).orElse(false)) errors.add("reuseDefinitionId '" + id + "' is not in the library; reuse only a listed widget, or choose another outcome");
            }
            case "proceed" -> {
                JsonNode brief = out.path("brief");
                if (!brief.isObject()) errors.add("proceed needs a brief");
                else {
                    if (brief.path("measures").size() == 0) errors.add("the brief needs at least one measure");
                    Layout.Page page = layouts.get(s.getLayoutId()).page(s.getPageId());
                    boolean pageHasClient = page != null && page.param("clientId") != null;
                    if ("client".equals(brief.path("scope").asText()) && !pageHasClient)
                        errors.add("this page does not supply a client (no clientId), so the scope must be book; if the banker needs one client's figures, decline and point them to Client 360");
                    if (template != null) errors.addAll(templateGate.intake(brief, template));
                }
            }
            case "redirect" -> {
                String id = out.path("suggestedTemplateId").asText("");
                if (others.stream().noneMatch(o -> o.id().equals(id)))
                    errors.add(others.isEmpty() ? "redirect is not available here; choose another outcome"
                            : "suggestedTemplateId '" + id + "' is not in <other_templates>; suggest one of: "
                              + String.join(", ", others.stream().map(SkillTemplate::id).toList()));
            }
            case "verify" -> {
                if (s.getSpec() == null) errors.add("there is no proposed widget to verify yet; choose another outcome");
            }
            default -> { }
        }
        return errors;
    }
}
