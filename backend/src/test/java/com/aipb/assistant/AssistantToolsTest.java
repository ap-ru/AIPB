package com.aipb.assistant;

import com.aipb.assistant.gates.BuildGate;
import com.aipb.assistant.gates.PresentGate;
import com.aipb.seed.Defaults;
import com.aipb.service.LayoutService;
import com.aipb.source.GraphMcpSource;
import com.aipb.source.SourceReader;
import com.aipb.widget.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private GraphMcpSource source;
    private AssistantTools tools;
    private WidgetSpecValidator validator;
    private BuildGate gate;
    private final AssistantTools.Context ctx = new AssistantTools.Context("default", "client", Map.of("clientId", "CL-0002"));

    @BeforeEach
    void setUp() throws Exception {
        source = mock(GraphMcpSource.class);
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

    private SourceReader.Result rows(int n) {
        ArrayNode a = mapper.createArrayNode();
        for (int i = 0; i < n; i++) a.addObject().put("i", i).put("note", "x".repeat(300)).set("tags", mapper.valueToTree(List.of(1, 2, 3, 4, 5, 6, 7)));
        return new SourceReader.Result(a, n, n, List.of());
    }

    @SuppressWarnings("unchecked")
    @Test
    void runQueryBindsThePageParamsAndShapesTheOutput() throws Exception {
        when(source.cypher(anyString(), any())).thenReturn(rows(25));
        String cypher = "MATCH (r:RevenueMonth) WHERE r.clientId = $clientId AND r.month >= datetime($asOfDate) RETURN r.totalUsd AS totalUsd";
        ObjectNode in = (ObjectNode) json("{\"params\":[{\"name\":\"clientId\",\"type\":\"string\"},{\"name\":\"asOfDate\",\"type\":\"date\"}]}");
        in.put("cypher", cypher);
        AssistantTools.Outcome out = tools.runQuery(in, ctx);
        assertThat(out.isError()).as(out.content()).isFalse();
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(source).cypher(eq(cypher), sent.capture());
        assertThat(sent.getValue()).containsEntry("clientId", "CL-0002").containsEntry("asOfDate", "2026-10-16T00:00:00Z");
        JsonNode r = json(out.content());
        assertThat(r.path("rowCount").asInt()).isEqualTo(25);
        assertThat(r.path("rows").size()).isEqualTo(AssistantTools.MAX_ROWS);
        assertThat(r.at("/rows/0/note").asText()).hasSize(AssistantTools.MAX_STRING + 1).endsWith("…");
        assertThat(r.at("/rows/0/tags").size()).isEqualTo(AssistantTools.MAX_ARRAY + 1);
        assertThat(r.path("note").asText()).contains("first 20 of 25");
        assertThat(out.summary()).isEqualTo("Ran a query on RevenueMonth: 25 rows");
    }

    @Test
    void runQueryExplainsWhatIsWrong() throws Exception {
        AssistantTools.Outcome undeclared = tools.runQuery(json("{\"cypher\":\"MATCH (h:Holding) WHERE h.clientId = $clientId RETURN h\",\"params\":[]}"), ctx);
        assertThat(undeclared.isError()).isTrue();
        assertThat(undeclared.content()).contains("not declared");
        AssistantTools.Outcome noCypher = tools.runQuery(json("{\"params\":[]}"), ctx);
        assertThat(noCypher.content()).contains("cypher is required");
        AssistantTools.Outcome noClient = tools.runQuery(json("{\"cypher\":\"MATCH (h:Holding) WHERE h.clientId = $clientId RETURN h\",\"params\":[{\"name\":\"clientId\",\"type\":\"string\"}]}"),
                new AssistantTools.Context("default", "command-center", Map.of()));
        assertThat(noClient.content()).contains("missing parameter clientId");
        verify(source, never()).cypher(anyString(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void sampleNodesAndDistinctValuesStaySmall() throws Exception {
        when(source.find(anyString(), any(), any(), any(), anyInt())).thenReturn(rows(2));
        tools.sampleNodes(json("{\"label\":\"Holding\",\"limit\":50,\"properties\":[\"productName\",\"drawnUsd\"]}"), ctx);
        verify(source).find(eq("Holding"), isNull(), eq(List.of("productName", "drawnUsd")), isNull(), eq(AssistantTools.MAX_SAMPLE));
        assertThat(tools.sampleNodes(json("{\"label\":\"Holding\",\"properties\":[\"bad field!\"]}"), ctx).isError()).isTrue();
        assertThat(tools.sampleNodes(json("{\"label\":\"Holding) DELETE (x\"}"), ctx).isError()).isTrue();

        when(source.cypher(anyString(), any())).thenReturn(new SourceReader.Result(json("[{\"value\":\"Closing\",\"count\":17},{\"value\":\"Discovery\",\"count\":84}]"), 2, 2, List.of()));
        AssistantTools.Outcome d = tools.distinctValues(json("{\"label\":\"Opportunity\",\"property\":\"stage\",\"filter\":{\"clientId\":\"CL-0002\"}}"), ctx);
        assertThat(json(d.content()).path("values").toString()).contains("Closing").contains("84");
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(source).cypher(sent.capture(), params.capture());
        assertThat(sent.getValue()).isEqualTo("MATCH (n:Opportunity) WHERE n.clientId = $f0 RETURN n.stage AS value, count(*) AS count "
                + "ORDER BY count DESC, value LIMIT " + AssistantTools.MAX_DISTINCT);
        assertThat(params.getValue().get("f0").toString()).contains("CL-0002");
        assertThat(tools.distinctValues(json("{\"label\":\"Opportunity\",\"property\":\"stage) RETURN 1 //\"}"), ctx).isError()).isTrue();
    }

    @Test
    void graphSchemaDescribesALabel() throws Exception {
        when(source.schema("Covenant")).thenReturn(json("{\"label\":\"Covenant\",\"properties\":[{\"name\":\"status\",\"types\":[\"String\"]}],"
                + "\"relationships\":[{\"type\":\"HAS_COVENANT\",\"direction\":\"in\",\"other\":\"Holding\",\"count\":192}]}"));
        AssistantTools.Outcome out = tools.graphSchema(json("{\"label\":\"Covenant\"}"), ctx);
        assertThat(out.isError()).isFalse();
        assertThat(out.content()).contains("HAS_COVENANT").contains("Holding");
    }

    // ------------------------------------------------------------------ build gate
    static final String SUBMISSION = """
            {"params":[{"name":"clientId","type":"string"}],
             "cypher":"MATCH (:Client {clientId: $clientId})-[:HOLDS]->(h:Holding) WHERE h.productType = 'credit' RETURN sum(h.drawnUsd) AS exposureUsd",
             "columns":[{"name":"exposureUsd","type":"number","unit":"USD","format":"currency","label":"Exposure"}],
             "measures":[{"column":"exposureUsd","definition":"Sum of drawnUsd over credit holdings","catalogReference":"Client.totalExposureUsd"}],
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
        String invented = SUBMISSION.replace("Client.totalExposureUsd", "Holding.exposureAmount");
        BuildGate.Result r = gate.check(json(invented), ctx, "t", "d");
        assertThat(r.accepted()).isFalse();
        assertThat(r.errors().get(0)).contains("Holding.exposureAmount").contains("valid names").contains("drawnUsd");
        assertThat(gate.check(json(SUBMISSION.replace("Client.totalExposureUsd", "holdings.drawnUsd")), ctx, "t", "d").errors().get(0))
                .contains("does not name a label");

        String noMeasure = SUBMISSION.replace("\"measures\":[{\"column\":\"exposureUsd\",\"definition\":\"Sum of drawnUsd over credit holdings\",\"catalogReference\":\"Client.totalExposureUsd\"}]", "\"measures\":[]");
        assertThat(gate.check(json(noMeasure), ctx, "t", "d").errors()).anyMatch(e -> e.contains("needs a measures entry"));
        verify(validator, never()).validate(any(), any(), any(), any());
    }

    @Test
    void validatorErrorsAndUnexpectedEmptyResultsGoBack() throws Exception {
        when(validator.validate(any(), any(), any(), any())).thenReturn(new ValidationReport(false,
                List.of(new ValidationReport.Issue("unit_format", "data.columns.exposureUsd", "format must be currency")), List.of(), Map.of(), null));
        assertThat(gate.check(json(SUBMISSION), ctx, "t", "d").errors()).containsExactly("unit_format (data.columns.exposureUsd): format must be currency");

        when(validator.validate(any(), any(), any(), any())).thenReturn(new ValidationReport(true, List.of(),
                List.of(new ValidationReport.Issue("empty", "data.custom.cypher", "the query returned no rows")), Map.of(),
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
                {"params":[{"name":"clientId","type":"string"}],"cypher":"MATCH (s:Signal) RETURN s.title AS title, s.severity AS severity",
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
        assertThat(r.spec().at("/data/custom/cypher").asText()).startsWith("MATCH (s:Signal)");
        assertThat(r.spec().at("/data/custom/columns/1/format").asText()).isEqualTo("badge");
        assertThat(r.spec().at("/data/custom/columns/1/tones/P1").asText()).isEqualTo("bad");
        assertThat(r.spec().at("/data/custom/columns/1/label").asText()).isEqualTo("Severity");
        assertThat(r.spec().at("/layout/span").asInt()).isEqualTo(5);

        JsonNode badTone = json(design.toString().replace("\"name\":\"severity\"", "\"name\":\"nope\""));
        assertThat(present.check(badTone, artifact, ctx).errors()).anyMatch(e -> e.contains("'nope'"));
    }

    @Test
    void unusedRoleValuesAreLeftOutOfTheView() throws Exception {
        PresentGate present = new PresentGate(validator, mapper);
        when(validator.validate(any(), any(), any(), any())).thenReturn(ok());
        JsonNode artifact = json("""
                {"params":[],"cypher":"MATCH (r:RevenueMonth) RETURN r.month AS quarter, sum(r.totalUsd) AS revenueUsd",
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
        assertThat(r.spec().at("/data/custom/columns/1/label").asText()).isEqualTo("Revenue");
        assertThat(r.spec().at("/data/custom/columns/1/format").asText()).isEqualTo("currency");
    }
}
