package com.aipb.assistant;

import com.aipb.assistant.gates.BuildGate;
import com.aipb.assistant.gates.PresentGate;
import com.aipb.assistant.llm.LlmMessage;
import com.aipb.assistant.llm.LlmRequest;
import com.aipb.assistant.stages.*;
import com.aipb.seed.Defaults;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceData;
import com.aipb.source.SourceReader;
import com.aipb.widget.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The assistant pipeline end to end with a scripted Claude: real stages, gates, context and routing; the data source
 * and the widget validator are faked. (The opt-in review stage is deliberately not exercised.)
 */
class AssistantOrchestratorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeGateway claude = new FakeGateway();
    private final Map<String, AssistantSession> store = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final AssistantEvents recorder = (event, data) -> {
        String detail = "";
        if (data instanceof Map<?, ?> m && m.get("status") != null && m.get("stage") != null) detail = m.get("stage") + "/" + m.get("status");
        else if (data instanceof Map<?, ?> m && m.get("kind") != null) detail = String.valueOf(m.get("kind"));
        else if (data instanceof JsonNode n && n.has("kind")) detail = n.get("kind").asText();
        events.add(event + ":" + detail);
    };
    private SourceData source;
    private WidgetSpecValidator validator;
    private WidgetDefinitionRepository definitions;
    private WidgetLibrary library;
    private WidgetPlacement placement;
    private WidgetDataService widgetData;
    private AssistantProperties props;
    private AssistantOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        source = mock(SourceData.class);
        when(source.catalog()).thenReturn(mapper.readTree(Path.of("../data/catalog.json").toFile()));
        when(source.asOfDate()).thenReturn("2026-10-16T00:00:00.000Z");
        when(source.aggregate(anyString(), any(ArrayNode.class))).thenReturn(result("[{\"quarter\":\"2026 Q3\",\"revenueUsd\":2700000}]"));
        LayoutService layouts = mock(LayoutService.class);
        when(layouts.get(anyString())).thenReturn(Defaults.defaultLayout());
        definitions = mock(WidgetDefinitionRepository.class);
        widgetData = mock(WidgetDataService.class);
        when(widgetData.options(any())).thenReturn(List.of(Map.of("value", "CL-0002", "label", "Northstar Communities")));
        validator = mock(WidgetSpecValidator.class);
        when(validator.validate(any(), any(), any(), any())).thenReturn(ok());
        library = mock(WidgetLibrary.class);
        placement = mock(WidgetPlacement.class);
        props = new AssistantProperties();

        ContextAssembler context = new ContextAssembler(source, new WidgetPrimitives(mapper), definitions, widgetData, layouts, mapper);
        AssistantTools tools = new AssistantTools(source, mapper);
        StageRunner runner = new StageRunner(claude, props, mapper);
        AssistantSessionRepository sessions = mock(AssistantSessionRepository.class);
        when(sessions.save(any())).thenAnswer(i -> { AssistantSession s = i.getArgument(0); store.put(s.getId(), s); return s; });
        when(sessions.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));

        orchestrator = new AssistantOrchestrator(sessions,
                new IntakeStage(runner, context, definitions, layouts, mapper),
                new BuildStage(runner, context, tools, new BuildGate(validator, context, mapper)),
                mock(ReviewStage.class),
                new PresentStage(runner, context, new PresentGate(validator, mapper), props, mapper),
                new ProposalBuilder(mapper, widgetData), library, definitions, placement, props, mapper);
    }

    // ------------------------------------------------------------------ scripts
    static final String BRIEF = """
            {"outcome":"proceed","message":"I'll build a quarterly revenue chart for this client.","reuseDefinitionId":null,"changed":[],
             "brief":{"question":"How has this client's revenue moved by quarter?","titleHint":"Quarterly revenue","scope":"client",
               "measures":[{"name":"Revenue","definition":"NII plus fees per quarter","unit":"USD"}],"dimensions":["quarter"],
               "time":{"window":"last 8 closed quarters","grain":"quarter"},"filters":[],"sort":"oldest first","limit":null,
               "display":"any","assumptions":["Last 8 closed quarters"]}}""";
    static final String RUN_QUERY = """
            {"collection":"revenue_monthly","params":[{"name":"clientId","type":"string"}],
             "pipeline":[{"$match":{"clientId":{"$param":"clientId"}}},{"$group":{"_id":null,"revenueUsd":{"$sum":"$totalUsd"}}}]}""";
    static final String SUBMIT = """
            {"collection":"revenue_monthly","params":[{"name":"clientId","type":"string"},{"name":"asOfDate","type":"date"}],
             "pipeline":[{"$match":{"clientId":{"$param":"clientId"}}},{"$project":{"_id":0,"quarter":1,"revenueUsd":1}}],
             "columns":[{"name":"quarter","type":"string","unit":"none","format":"text","label":"Quarter"},
                        {"name":"revenueUsd","type":"number","unit":"USD","format":"currency","label":"Revenue"}],
             "measures":[{"column":"revenueUsd","definition":"Sum of totalUsd per quarter","catalogReference":"revenue_monthly.totalUsd"}],
             "expectedShape":"8 rows, one per quarter","emptyIsExpected":false,"assumptions":["Quarters are calendar quarters"]}""";
    static final String DESIGN = """
            {"title":"Quarterly revenue","description":"Revenue for the selected client over the last 8 closed quarters.","primitive":"bar",
             "roles":{"value":null,"compare":null,"secondary":null,"x":"quarter","y":["revenueUsd"],"orientation":null,"stacked":null,
                      "columns":null,"title":null,"subtitle":null,"badge":null,"meta":null},"span":7,"columnOverrides":[]}""";

    private String newSession() {
        return orchestrator.create("default", "client", Map.of("clientId", "CL-0002"), "demo-builder").getId();
    }

    // ------------------------------------------------------------------ tests
    @Test
    void happyPathBuildsChecksDesignsAndProposes() {
        when(validator.validate(any(), any(), any(), any())).thenReturn(failing("empty", "the query returned no rows"), ok(), ok());
        claude.on("intake", FakeGateway.json(BRIEF))
              .on("build", FakeGateway.tool("run_query", RUN_QUERY), FakeGateway.tool("submit_data", SUBMIT), FakeGateway.tool("submit_data", SUBMIT))
              .on("present", FakeGateway.json(DESIGN));
        String id = newSession();

        orchestrator.message(id, "Show me revenue by quarter", Map.of("clientId", "CL-0002"), recorder);

        AssistantSession s = store.get(id);
        assertThat(s.getStatus()).isEqualTo(AssistantSession.PROPOSED);
        assertThat(claude.stagesCalled()).containsExactly("intake", "build", "build", "build", "present");
        JsonNode p = read(s.getProposal());
        assertThat(p.path("kind").asText()).isEqualTo("new");
        assertThat(p.path("title").asText()).isEqualTo("Quarterly revenue");
        assertThat(p.path("spec").path("view").path("primitive").asText()).isEqualTo("bar");
        assertThat(p.path("whatItShows").get(0).path("source").asText()).isEqualTo("revenue_monthly.totalUsd");
        assertThat(p.path("assumptions").toString()).contains("Last 8 closed quarters").contains("calendar quarters");
        assertThat(p.path("review").path("state").asText()).isEqualTo("none");
        // the gate's rejection went back to Claude as an error tool result, and the conversation stayed append-only
        LlmRequest third = claude.requests.get(3);
        assertThat(third.messages()).hasSize(5);
        LlmMessage.User rejected = (LlmMessage.User) third.messages().get(4);
        LlmMessage.ToolResult tr = (LlmMessage.ToolResult) rejected.blocks().get(0);
        assertThat(tr.isError()).isTrue();
        assertThat(tr.content()).contains("Not accepted").contains("empty");
        assertThat(third.messages().get(0)).isEqualTo(claude.requests.get(1).messages().get(0));
        // static system prompts are identical on every call of a stage (cacheable)
        assertThat(claude.requests.get(1).system()).isEqualTo(claude.requests.get(3).system());
        assertThat(events).contains("stage:intake/started", "stage:build/done", "stage:present/done", "proposal:new", "done:");
        assertThat(s.getRuns()).extracting(AssistantSession.StageRun::getStage).containsExactly("intake", "build", "present");
        assertThat(s.getRuns().get(1).getToolCalls()).extracting(AssistantSession.ToolCall::getName).containsExactly("run_query", "submit_data", "submit_data");
        assertThat(s.getRuns().get(1).getManifest()).extracting(ContextAssembler.ManifestEntry::block).contains("data-dictionary", "user-context");
        assertThat(s.getRuns().get(0).getCostUsd()).isGreaterThan(0);
    }

    @Test
    void clarifyAnswerAndDeclineReplyWithoutBuilding() {
        for (String outcome : List.of("clarify", "answer", "decline")) {
            claude.on("intake", FakeGateway.json("{\"outcome\":\"" + outcome + "\",\"message\":\"Which period - this year or the last 12 months?\",\"brief\":null,\"reuseDefinitionId\":null,\"changed\":[]}"));
            String id = newSession();
            orchestrator.message(id, "revenue?", null, recorder);
            AssistantSession s = store.get(id);
            assertThat(s.getStatus()).isEqualTo(AssistantSession.AWAITING_USER);
            assertThat(s.getTurns().get(s.getTurns().size() - 1).getKind()).isEqualTo(outcome);
        }
        assertThat(claude.stagesCalled()).containsOnly("intake");
    }

    @Test
    void reuseProposesTheExistingWidget() {
        WidgetDefinition d = new WidgetDefinition();
        d.setId("client-revenue-trend");
        d.setTitle("Revenue trend");
        d.setPrimitive("bar");
        when(definitions.existsById("client-revenue-trend")).thenReturn(true);
        when(definitions.findById("client-revenue-trend")).thenReturn(Optional.of(d));
        when(widgetData.data(eq("client-revenue-trend"), any())).thenReturn(new WidgetDataService.DataResult(List.of(), mapper.createArrayNode(), 0, 0, false, "2026-10-16", Map.of(), List.of(), null));
        when(widgetData.spec("client-revenue-trend")).thenReturn(spec());
        claude.on("intake", FakeGateway.json("{\"outcome\":\"reuse\",\"message\":\"There's already a widget for this.\",\"brief\":null,\"reuseDefinitionId\":\"client-revenue-trend\",\"changed\":[]}"));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        JsonNode p = read(store.get(id).getProposal());
        assertThat(p.path("kind").asText()).isEqualTo("reuse");
        assertThat(p.path("definitionId").asText()).isEqualTo("client-revenue-trend");
    }

    @Test
    void anIntakeGateRejectionIsCorrectedInTheSameConversation() {
        claude.on("intake",
                FakeGateway.json("{\"outcome\":\"reuse\",\"message\":\"Use this.\",\"brief\":null,\"reuseDefinitionId\":\"no-such-widget\",\"changed\":[]}"),
                FakeGateway.json("{\"outcome\":\"clarify\",\"message\":\"Which measure?\",\"brief\":null,\"reuseDefinitionId\":null,\"changed\":[]}"));
        String id = newSession();
        orchestrator.message(id, "show me that", null, recorder);
        LlmRequest retry = claude.requests.get(1);
        assertThat(retry.messages()).hasSize(3);
        assertThat(((LlmMessage.Text) ((LlmMessage.User) retry.messages().get(2)).blocks().get(0)).text()).contains("no-such-widget").contains("not in the library");
        assertThat(store.get(id).getStatus()).isEqualTo(AssistantSession.AWAITING_USER);
    }

    @Test
    void aClientScopedBriefOnABookPageIsSentBack() {
        claude.on("intake", FakeGateway.json(BRIEF),
                FakeGateway.json("{\"outcome\":\"decline\",\"message\":\"Client widgets belong on Client 360.\",\"brief\":null,\"reuseDefinitionId\":null,\"changed\":[]}"));
        String id = orchestrator.create("default", "command-center", Map.of(), "demo-builder").getId();
        orchestrator.message(id, "show this client's revenue", null, recorder);
        assertThat(((LlmMessage.Text) ((LlmMessage.User) claude.requests.get(1).messages().get(2)).blocks().get(0)).text()).contains("scope must be book");
        assertThat(store.get(id).getTurns().get(1).getKind()).isEqualTo("decline");
    }

    @Test
    void aRefusalStopsTheRunHonestly() {
        claude.on("intake", FakeGateway.json(BRIEF)).on("build", FakeGateway.refusal("cyber"));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        AssistantSession s = store.get(id);
        assertThat(s.getStatus()).isEqualTo(AssistantSession.FAILED);
        assertThat(s.getTurns().get(s.getTurns().size() - 1).getText()).contains("declined").contains("cyber");
        assertThat(s.getRuns().get(1).getOutcome()).isEqualTo("failed");
        assertThat(events).contains("error:");
    }

    @Test
    void aBuildThatNeverSubmitsIsNudgedOnceThenStopped() {
        claude.on("intake", FakeGateway.json(BRIEF)).on("build", FakeGateway.text("Done, looks good."), FakeGateway.text("All set."));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        assertThat(((LlmMessage.Text) ((LlmMessage.User) claude.requests.get(2).messages().get(2)).blocks().get(0)).text()).contains("submit_data");
        assertThat(store.get(id).getTurns().get(store.get(id).getTurns().size() - 1).getText()).contains("without submitting");
    }

    @Test
    void theToolCallCapStopsARunawayLoop() {
        props.setMaxToolCalls(3);
        claude.on("intake", FakeGateway.json(BRIEF))
              .on("build", FakeGateway.tool("run_query", RUN_QUERY), FakeGateway.tool("run_query", RUN_QUERY), FakeGateway.tool("run_query", RUN_QUERY), FakeGateway.tool("run_query", RUN_QUERY));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        assertThat(store.get(id).getStatus()).isEqualTo(AssistantSession.FAILED);
        assertThat(store.get(id).getTurns().get(store.get(id).getTurns().size() - 1).getText()).contains("3 tool calls");
    }

    @Test
    void aPresentationOnlyRevisionRerunsOnlyTheDesign() {
        claude.on("intake", FakeGateway.json(BRIEF)).on("build", FakeGateway.tool("submit_data", SUBMIT)).on("present", FakeGateway.json(DESIGN));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        claude.requests.clear();
        claude.on("intake", FakeGateway.json(BRIEF.replace("\"changed\":[]", "\"changed\":[\"presentation\"]")))
              .on("present", FakeGateway.json(DESIGN.replace("\"Quarterly revenue\",\"description\"", "\"Revenue by quarter\",\"description\"")));
        orchestrator.message(id, "call it Revenue by quarter", null, recorder);
        assertThat(claude.stagesCalled()).containsExactly("intake", "present");
        assertThat(read(store.get(id).getProposal()).path("title").asText()).isEqualTo("Revenue by quarter");
        // the intake saw the current widget, and the design saw the change request
        assertThat(((LlmMessage.Text) ((LlmMessage.User) claude.requests.get(0).messages().get(0)).blocks().get(0)).text()).contains("<current_widget>");
        assertThat(((LlmMessage.Text) ((LlmMessage.User) claude.requests.get(1).messages().get(0)).blocks().get(0)).text()).contains("<change_request>");
    }

    @Test
    void aDataRevisionRebuildsFromThePreviousData() {
        claude.on("intake", FakeGateway.json(BRIEF)).on("build", FakeGateway.tool("submit_data", SUBMIT)).on("present", FakeGateway.json(DESIGN));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        claude.requests.clear();
        claude.on("intake", FakeGateway.json(BRIEF.replace("\"changed\":[]", "\"changed\":[\"window\"]")))
              .on("build", FakeGateway.tool("submit_data", SUBMIT)).on("present", FakeGateway.json(DESIGN));
        orchestrator.message(id, "make it the last 12 quarters", null, recorder);
        assertThat(claude.stagesCalled()).containsExactly("intake", "build", "present");
        String buildContext = ((LlmMessage.Text) ((LlmMessage.User) claude.requests.get(1).messages().get(0)).blocks().get(0)).text();
        assertThat(buildContext).contains("<previous_data>").contains("<change_request>").contains("last 12 quarters").doesNotContain("\"preview\"");
    }

    @Test
    void approvingSavesToThePersonalLibraryWithProvenancePlacesItAndOffersReview() {
        String id = proposedSession();
        when(definitions.existsById("quarterly-revenue")).thenReturn(true); // taken: the new widget gets a suffix

        orchestrator.approve(id, false);

        verify(library).create(argThat(spec -> spec.path("id").asText().equals("quarterly-revenue-2")
                && spec.path("provenance").path("reviewed").asText().equals("none")
                && spec.path("provenance").path("question").asText().contains("revenue")),
                eq("assistant"), eq("demo-builder"), eq(WidgetDefinition.PERSONAL), eq("default"), eq("client"), any());
        verify(placement).placeDefinition("default", "client", "quarterly-revenue-2", "assistant");
        AssistantSession s = store.get(id);
        assertThat(s.getStatus()).isEqualTo(AssistantSession.PLACED);
        assertThat(s.getTurns().get(s.getTurns().size() - 1).getKind()).isEqualTo("submit-prompt");
        assertThat(s.getSubmission()).isEqualTo("none");
    }

    @Test
    void submittingAfterApprovalMarksItPending() {
        String id = proposedSession();
        orchestrator.approve(id, false);
        WidgetDefinition pending = new WidgetDefinition();
        pending.setId("quarterly-revenue");
        pending.setTitle("Quarterly revenue");
        WidgetDefinition.Review review = new WidgetDefinition.Review();
        review.setStatus("pending");
        pending.setReview(review);
        when(library.submitForReview("quarterly-revenue", "demo-builder", "Matches the finance pack")).thenReturn(pending);

        Map<String, Object> out = orchestrator.submit(id, "Matches the finance pack");

        assertThat(out).containsEntry("review", "pending");
        AssistantSession s = store.get(id);
        assertThat(s.getSubmission()).isEqualTo("pending");
        assertThat(s.getTurns().get(s.getTurns().size() - 1).getText()).contains("Submitted \"Quarterly revenue\" for review");
        assertThatThrownBy(() -> orchestrator.submit(id, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already");
    }

    @Test
    void submittingBeforeApprovalIsRefused() {
        String id = proposedSession();
        assertThatThrownBy(() -> orchestrator.submit(id, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("add the widget");
        verify(library, never()).submitForReview(any(), any(), any());
    }

    @Test
    void aSessionBelongsToItsOwner() {
        String id = newSession();
        assertThat(orchestrator.get(id, "demo-builder").getId()).isEqualTo(id);
        assertThatThrownBy(() -> orchestrator.get(id, "demo-reviewer")).isInstanceOf(NoSuchElementException.class);
    }

    /** A session with a new widget proposed; approving it saves "quarterly-revenue" (or the id the test makes it take). */
    private String proposedSession() {
        claude.on("intake", FakeGateway.json(BRIEF)).on("build", FakeGateway.tool("submit_data", SUBMIT)).on("present", FakeGateway.json(DESIGN));
        String id = newSession();
        orchestrator.message(id, "revenue by quarter", null, recorder);
        when(library.create(any(), eq("assistant"), any(), any(), any(), any(), any())).thenAnswer(i -> {
            WidgetDefinition d = new WidgetDefinition();
            d.setId(((JsonNode) i.getArgument(0)).path("id").asText());
            return new WidgetLibrary.Saved(d, ok());
        });
        return id;
    }

    // ------------------------------------------------------------------ helpers
    private ValidationReport ok() {
        JsonNode rows = readUnchecked("[{\"quarter\":\"2026 Q3\",\"revenueUsd\":2700000}]");
        return new ValidationReport(true, List.of(), List.of(), Map.of("clientId", "CL-0002"),
                new ValidationReport.Preview(List.of(), rows, 1, 1, false, "2026-10-16T00:00:00.000Z", List.of()));
    }

    private ValidationReport failing(String code, String message) {
        return new ValidationReport(false, List.of(new ValidationReport.Issue(code, "data.pipeline", message)), List.of(), Map.of(), null);
    }

    private SourceReader.Result result(String rows) {
        JsonNode r = readUnchecked(rows);
        return new SourceReader.Result(r, r.size(), r.size(), List.of());
    }

    private WidgetSpec spec() {
        return SpecMigrator.read(mapper, readUnchecked("{\"id\":\"x\",\"title\":\"x\",\"description\":\"x\",\"data\":{\"collection\":\"c\",\"pipeline\":[{}],\"columns\":[]},\"view\":{\"primitive\":\"bar\",\"x\":\"q\",\"y\":[\"v\"]}}"));
    }

    private JsonNode read(String json) { return readUnchecked(json); }

    private JsonNode readUnchecked(String json) {
        try { return mapper.readTree(json); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
