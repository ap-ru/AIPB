package com.aipb.assistant;

import com.aipb.assistant.gates.BuildGate;
import com.aipb.assistant.gates.PresentGate;
import com.aipb.seed.Defaults;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceData;
import com.aipb.widget.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The assistant's data tools and gates: binding, shaping, input validation, and the checks a submission must pass. */
class AssistantToolsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SourceData source;
    private AssistantTools tools;
    private WidgetSpecValidator validator;
    private BuildGate gate;
    private final AssistantTools.Context ctx = new AssistantTools.Context("default", "client", Map.of("clientId", "CL-0002"));

    @BeforeEach
    void setUp() throws Exception {
        source = mock(SourceData.class);
        when(source.catalog()).thenReturn(mapper.readTree(Path.of("../data/catalog.json").toFile()));
        when(source.asOfDate()).thenReturn("2026-10-16T00:00:00.000Z");
        tools = new AssistantTools(source, mapper);
        validator = mock(WidgetSpecValidator.class);
        LayoutService layouts = mock(LayoutService.class);
        when(layouts.get(anyString())).thenReturn(Defaults.defaultLayout());
        ContextAssembler context = new ContextAssembler(source, new WidgetPrimitives(mapper), mock(WidgetDefinitionRepository.class),
                mock(WidgetDataService.class), layouts, mapper);
        gate = new BuildGate(validator, context, mapper);
    }

    private JsonNode json(String s) throws Exception { return mapper.readTree(s); }

    private SourceData.Result rows(int n) {
        ArrayNode a = mapper.createArrayNode();
        for (int i = 0; i < n; i++) a.addObject().put("i", i).put("note", "x".repeat(300)).set("tags", mapper.valueToTree(List.of(1, 2, 3, 4, 5, 6, 7)));
        return new SourceData.Result(a, n, n, List.of());
    }

    @Test
    void runQueryBindsThePageParamsAndShapesTheOutput() throws Exception {
        when(source.aggregate(anyString(), any(ArrayNode.class))).thenReturn(rows(25));
        AssistantTools.Outcome out = tools.runQuery(json("""
                {"collection":"revenue_monthly","params":[{"name":"clientId","type":"string"},{"name":"asOfDate","type":"date"}],
                 "pipeline":[{"$match":{"clientId":{"$param":"clientId"},"$expr":{"$gte":["$month",{"$param":"asOfDate"}]}}}]}"""), ctx);
        assertThat(out.isError()).isFalse();
        ArgumentCaptor<ArrayNode> sent = ArgumentCaptor.forClass(ArrayNode.class);
        verify(source).aggregate(eq("revenue_monthly"), sent.capture());
        assertThat(sent.getValue().at("/0/$match/clientId").asText()).isEqualTo("CL-0002");
        assertThat(sent.getValue().at("/0/$match/$expr/$gte/1/$date").asText()).isEqualTo("2026-10-16T00:00:00Z");
        JsonNode r = json(out.content());
        assertThat(r.path("rowCount").asInt()).isEqualTo(25);
        assertThat(r.path("rows").size()).isEqualTo(AssistantTools.MAX_ROWS);
        assertThat(r.at("/rows/0/note").asText()).hasSize(AssistantTools.MAX_STRING + 1).endsWith("…");
        assertThat(r.at("/rows/0/tags").size()).isEqualTo(AssistantTools.MAX_ARRAY + 1);
        assertThat(r.path("note").asText()).contains("first 20 of 25");
        assertThat(out.summary()).isEqualTo("Ran a query on revenue_monthly: 25 rows");
    }

    @Test
    void runQueryExplainsWhatIsWrong() throws Exception {
        AssistantTools.Outcome undeclared = tools.runQuery(json("{\"collection\":\"holdings\",\"params\":[],\"pipeline\":[{\"$match\":{\"clientId\":{\"$param\":\"clientId\"}}}]}"), ctx);
        assertThat(undeclared.isError()).isTrue();
        assertThat(undeclared.content()).contains("not declared");
        AssistantTools.Outcome badStage = tools.runQuery(json("{\"collection\":\"holdings\",\"params\":[],\"pipeline\":[{\"$match\":{},\"$limit\":5}]}"), ctx);
        assertThat(badStage.content()).contains("exactly one operator");
        AssistantTools.Outcome noClient = tools.runQuery(json("{\"collection\":\"holdings\",\"params\":[{\"name\":\"clientId\",\"type\":\"string\"}],\"pipeline\":[{\"$match\":{\"clientId\":{\"$param\":\"clientId\"}}}]}"),
                new AssistantTools.Context("default", "command-center", Map.of()));
        assertThat(noClient.content()).contains("missing parameter clientId");
        verify(source, never()).aggregate(anyString(), any(ArrayNode.class));
    }

    @Test
    void sampleDocumentsAndDistinctValuesStaySmall() throws Exception {
        when(source.find(anyString(), any(), any(), any(), anyInt())).thenReturn(rows(2));
        tools.sampleDocuments(json("{\"collection\":\"holdings\",\"limit\":50,\"fields\":[\"productName\",\"drawnUsd\"]}"), ctx);
        verify(source).find(eq("holdings"), isNull(), argThat(p -> p.keySet().equals(Set.of("productName", "drawnUsd"))), isNull(), eq(AssistantTools.MAX_SAMPLE));
        assertThat(tools.sampleDocuments(json("{\"collection\":\"holdings\",\"fields\":[\"bad field!\"]}"), ctx).isError()).isTrue();

        when(source.aggregate(anyString(), any(ArrayNode.class))).thenReturn(new SourceData.Result(json("[{\"_id\":\"Closing\",\"count\":17},{\"_id\":\"Discovery\",\"count\":84}]"), 2, 2, List.of()));
        AssistantTools.Outcome d = tools.distinctValues(json("{\"collection\":\"opportunities\",\"field\":\"stage\"}"), ctx);
        assertThat(json(d.content()).path("values").toString()).contains("Closing").contains("84");
        ArgumentCaptor<ArrayNode> sent = ArgumentCaptor.forClass(ArrayNode.class);
        verify(source).aggregate(eq("opportunities"), sent.capture());
        assertThat(sent.getValue().at("/1/$group/_id").asText()).isEqualTo("$stage");
        assertThat(sent.getValue().at("/3/$limit").asInt()).isEqualTo(AssistantTools.MAX_DISTINCT);
    }

    // ------------------------------------------------------------------ build gate
    static final String SUBMISSION = """
            {"collection":"holdings","params":[{"name":"clientId","type":"string"}],
             "pipeline":[{"$match":{"clientId":{"$param":"clientId"},"productType":"credit"}},{"$group":{"_id":null,"exposureUsd":{"$sum":"$drawnUsd"}}}],
             "columns":[{"name":"exposureUsd","type":"number","unit":"USD","format":"currency","label":"Exposure"}],
             "measures":[{"column":"exposureUsd","definition":"Sum of drawnUsd over credit holdings","catalogReference":"clients.totalExposureUsd"}],
             "expectedShape":"one row","emptyIsExpected":false,"assumptions":[]}""";

    private ValidationReport ok() {
        return new ValidationReport(true, List.of(), List.of(), Map.of("clientId", "CL-0002"),
                new ValidationReport.Preview(List.of(), mapper.createArrayNode(), 1, 1, false, "2026-10-16", List.of()));
    }

    @Test
    void aGoodSubmissionIsAcceptedWithItsPreview() throws Exception {
        when(validator.validate(any(), any(), any(), any())).thenReturn(ok());
        BuildGate.Result r = gate.check(json(SUBMISSION), ctx, "Exposure", "Credit exposure for the client");
        assertThat(r.accepted()).as(r.errors().toString()).isTrue();
        assertThat(r.artifact().has("preview")).isTrue();
        // the validator saw the data as a table of all columns, against this page with the sample params
        ArgumentCaptor<JsonNode> spec = ArgumentCaptor.forClass(JsonNode.class);
        verify(validator).validate(spec.capture(), eq("default"), eq("client"), eq(Map.of("clientId", "CL-0002")));
        assertThat(spec.getValue().at("/view/primitive").asText()).isEqualTo("table");
        assertThat(spec.getValue().at("/view/columns/0").asText()).isEqualTo("exposureUsd");
    }

    @Test
    void inventedDefinitionsAndUnexplainedNumbersAreRejected() throws Exception {
        String invented = SUBMISSION.replace("clients.totalExposureUsd", "holdings.exposureAmount");
        BuildGate.Result r = gate.check(json(invented), ctx, "t", "d");
        assertThat(r.accepted()).isFalse();
        assertThat(r.errors().get(0)).contains("holdings.exposureAmount").contains("valid names").contains("drawnUsd");

        String noMeasure = SUBMISSION.replace("\"measures\":[{\"column\":\"exposureUsd\",\"definition\":\"Sum of drawnUsd over credit holdings\",\"catalogReference\":\"clients.totalExposureUsd\"}]", "\"measures\":[]");
        assertThat(gate.check(json(noMeasure), ctx, "t", "d").errors()).anyMatch(e -> e.contains("needs a measures entry"));
        verify(validator, never()).validate(any(), any(), any(), any());
    }

    @Test
    void validatorErrorsAndUnexpectedEmptyResultsGoBack() throws Exception {
        when(validator.validate(any(), any(), any(), any())).thenReturn(new ValidationReport(false,
                List.of(new ValidationReport.Issue("unit_format", "data.columns.exposureUsd", "format must be currency")), List.of(), Map.of(), null));
        assertThat(gate.check(json(SUBMISSION), ctx, "t", "d").errors()).containsExactly("unit_format (data.columns.exposureUsd): format must be currency");

        when(validator.validate(any(), any(), any(), any())).thenReturn(new ValidationReport(true, List.of(),
                List.of(new ValidationReport.Issue("empty", "data.pipeline", "the query returned no rows")), Map.of(),
                new ValidationReport.Preview(List.of(), mapper.createArrayNode(), 0, 0, false, "2026-10-16", List.of())));
        assertThat(gate.check(json(SUBMISSION), ctx, "t", "d").errors()).anyMatch(e -> e.contains("emptyIsExpected"));
        assertThat(gate.check(json(SUBMISSION.replace("\"emptyIsExpected\":false", "\"emptyIsExpected\":true")), ctx, "t", "d").accepted()).isTrue();
    }

    // ------------------------------------------------------------------ present gate
    @Test
    void theDesignIsAssembledIntoAFullSpec() throws Exception {
        PresentGate present = new PresentGate(validator, mapper);
        when(validator.validate(any(), any(), any(), any())).thenReturn(ok());
        JsonNode artifact = json("""
                {"params":[{"name":"clientId","type":"string"}],"collection":"signals","pipeline":[{"$match":{}}],
                 "columns":[{"name":"title","type":"string","unit":"none","format":"text","label":"Signal"},
                            {"name":"severity","type":"string","unit":"none","format":"text","label":"Sev"}]}""");
        JsonNode design = json("""
                {"title":"Active signals!","description":"Unresolved signals.","primitive":"list",
                 "roles":{"value":null,"compare":null,"secondary":null,"x":null,"y":null,"orientation":null,"stacked":null,"columns":null,
                          "title":"title","subtitle":null,"badge":"severity","meta":null},"span":5,
                 "columnOverrides":[{"name":"severity","label":"Severity","badgeTones":[{"value":"P1","tone":"bad"},{"value":"P2","tone":"warn"}]}]}""");
        PresentGate.Result r = present.check(design, artifact, ctx);
        assertThat(r.accepted()).isTrue();
        assertThat(r.spec().path("id").asText()).isEqualTo("active-signals");
        assertThat(r.spec().path("view").toString()).isEqualTo("{\"primitive\":\"list\",\"title\":\"title\",\"badge\":\"severity\"}");
        assertThat(r.spec().at("/data/columns/1/format").asText()).isEqualTo("badge");
        assertThat(r.spec().at("/data/columns/1/tones/P1").asText()).isEqualTo("bad");
        assertThat(r.spec().at("/data/columns/1/label").asText()).isEqualTo("Severity");
        assertThat(r.spec().at("/layout/span").asInt()).isEqualTo(5);

        JsonNode badTone = json(design.toString().replace("\"name\":\"severity\"", "\"name\":\"nope\""));
        assertThat(present.check(badTone, artifact, ctx).errors()).anyMatch(e -> e.contains("'nope'"));
    }

    @Test
    void unusedRoleValuesAreLeftOutOfTheView() throws Exception {
        PresentGate present = new PresentGate(validator, mapper);
        when(validator.validate(any(), any(), any(), any())).thenReturn(ok());
        JsonNode artifact = json("""
                {"params":[],"collection":"revenue","pipeline":[{"$match":{}}],
                 "columns":[{"name":"quarter","type":"string","unit":"none","format":"text","label":"Quarter"},
                            {"name":"revenueUsd","type":"number","unit":"usd","format":"currency","label":"Revenue"}]}""");
        JsonNode design = json("""
                {"title":"Quarterly revenue","description":"Revenue by quarter.","primitive":"bar",
                 "roles":{"value":"","compare":"","secondary":"","x":"quarter","y":["revenueUsd"],"orientation":"default","stacked":false,
                          "columns":[],"title":"","subtitle":"","badge":"","meta":"","reference":"","emphasis":"latest","polarity":"higherIsBetter",
                          "headline":"default","warnAt":0},"span":6,
                 "columnOverrides":[{"name":"revenueUsd","label":"","badgeTones":[]}]}""");
        PresentGate.Result r = present.check(design, artifact, ctx);
        assertThat(r.accepted()).isTrue();
        assertThat(r.spec().path("view").toString()).isEqualTo(
                "{\"primitive\":\"bar\",\"x\":\"quarter\",\"y\":[\"revenueUsd\"],\"emphasis\":\"latest\",\"polarity\":\"higherIsBetter\"}");
        assertThat(r.spec().at("/data/columns/1/label").asText()).isEqualTo("Revenue");
        assertThat(r.spec().at("/data/columns/1/format").asText()).isEqualTo("currency");
    }
}
