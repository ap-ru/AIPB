package com.aipb.assistant.stages;

import com.aipb.assistant.AssistantSession;
import com.aipb.assistant.ContextAssembler;
import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.widget.WidgetDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * menu (static); the page, the data's today, the library, the current brief and the conversation (dynamic). Gate:
 * a reuse names a real library widget, a brief has a measure and a scope the page supports, a verify has something
 * to verify, and every reply carries a message.
 */
@Component
public class IntakeStage {

    static final int MAX_TURNS_IN_CONTEXT = 12;

    private final StageRunner runner;
    private final ContextAssembler context;
    private final WidgetDefinitionRepository library;
    private final LayoutService layouts;
    private final JsonNode schema;

    public IntakeStage(StageRunner runner, ContextAssembler context, WidgetDefinitionRepository library, LayoutService layouts,
                       ObjectMapper mapper) {
        this.runner = runner;
        this.context = context;
        this.library = library;
        this.layouts = layouts;
        try (InputStream in = new ClassPathResource("assistant/schemas/intake.json").getInputStream()) {
            this.schema = mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public JsonNode run(AssistantSession s, String bankerMessage, RunRecorder rec, Instant deadline) {
        List<ContextAssembler.Block> system = context.intakeSystem();
        String user = userContext(s, bankerMessage);
        rec.context(context.manifest(system, user), user);
        return runner.structured("intake", system, user, schema, out -> gate(out, s), 2, rec, deadline);
    }

    String userContext(AssistantSession s, String bankerMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append(context.page(s.getLayoutId(), s.getPageId(), s.getParams()));
        sb.append(context.dataToday());
        sb.append(context.library(s.getOwner()));
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

    List<String> gate(JsonNode out, AssistantSession s) {
        List<String> errors = new ArrayList<>();
        String outcome = out.path("outcome").asText();
        if (out.path("message").asText("").isBlank()) errors.add("message is required for every outcome");
        switch (outcome) {
            case "reuse" -> {
                String id = out.path("reuseDefinitionId").asText("");
                if (!library.findById(id).map(d -> d.visibleTo(s.getOwner())).orElse(false)) errors.add("reuseDefinitionId '" + id + "' is not in the library; reuse only a listed widget, or choose another outcome");
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
                }
            }
            case "verify" -> {
                if (s.getSpec() == null) errors.add("there is no proposed widget to verify yet; choose another outcome");
            }
            default -> { }
        }
        return errors;
    }
}
