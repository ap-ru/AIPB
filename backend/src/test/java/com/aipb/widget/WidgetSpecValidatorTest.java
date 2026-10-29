package com.aipb.widget;

import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.source.GraphMcpSource;
import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** The validator's static and live rules, against a fake source that returns hand-made results. */
class WidgetSpecValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private GraphMcpSource source;
    private WidgetSpecValidator validator;
    private Layout layout;

    @BeforeEach
    void setUp() {
        source = mock(GraphMcpSource.class);
        when(source.labelsCached()).thenReturn(List.of("Client", "Covenant", "RevenueMonth"));
        when(source.asOfDate()).thenReturn("2026-10-16T00:00:00.000Z");
        LayoutService layouts = mock(LayoutService.class);
        layout = new Layout();
        Layout.Page client = new Layout.Page();
        client.setId("client");
        client.setTitle("Client 360");
        Layout.PageParam p = new Layout.PageParam();
        p.setName("clientId");
        client.getParams().add(p);
        Layout.Page cc = new Layout.Page();
        cc.setId("command-center");
        cc.setTitle("Command Center");
        layout.getPages().addAll(List.of(client, cc));
        when(layouts.get(anyString())).thenReturn(layout);
        WidgetPrimitives primitives = new WidgetPrimitives(mapper);
        WidgetDataService data = new WidgetDataService(source, mock(WidgetDefinitionRepository.class), mapper,
                new com.aipb.semantic.QueryExecutor(com.aipb.semantic.SemanticModel.fromClasspath(mapper), source));
        validator = new WidgetSpecValidator(primitives, data, source, layouts, mapper);
    }

    /** A valid client-scoped table spec; tests break one thing at a time. */
    private ObjectNode spec() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"id":"client-covenants","specVersion":2,"title":"Covenants","description":"Covenants of the client.",
                 "params":[{"name":"clientId","type":"string"}],
                 "data":{"custom":{
                   "cypher":"MATCH (c:Covenant)-[:FOR_CLIENT]->(:Client {clientId: $clientId}) RETURN c.name AS name, c.headroomPct AS headroomPct, c.lastTestDate AS lastTestDate",
                   "columns":[{"name":"name","type":"string","label":"Covenant"},
                              {"name":"headroomPct","type":"number","unit":"percent","format":"percentPoints","label":"Headroom"},
                              {"name":"lastTestDate","type":"date","unit":"date","format":"date","label":"Tested"}]}},
                 "view":{"primitive":"table","columns":["name","headroomPct","lastTestDate"]}}""");
    }

    private void returns(String rowsJson, String... limits) throws Exception {
        JsonNode rows = mapper.readTree(rowsJson);
        when(source.cypher(anyString(), any()))
                .thenReturn(new SourceReader.Result(rows, rows.size(), rows.size(), List.of(limits)));
    }

    private static final String GOOD_ROWS = """
            [{"name":"Max Total Leverage","headroomPct":22.5,"lastTestDate":"2026-09-30T00:00:00.000Z"},
             {"name":"Min Liquidity","headroomPct":5.9,"lastTestDate":"2026-09-30T00:00:00.000Z"}]""";

    private List<String> errors(ValidationReport r) { return r.errors().stream().map(ValidationReport.Issue::code).toList(); }

    @Test
    void validSpecPassesWithAPreview() throws Exception {
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(spec(), "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        assertThat(r.preview().count()).isEqualTo(2);
        assertThat(r.preview().rows().get(0).get("headroomPct").asDouble()).isEqualTo(22.5);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(source).cypher(startsWith("MATCH (c:Covenant)"), sent.capture());
        assertThat(sent.getValue()).containsEntry("clientId", "CL-0001");
    }

    @Test
    void schemaViolationsStopEverything() throws Exception {
        ObjectNode s = spec();
        s.remove("view");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).containsOnly("schema");
        verify(source, never()).cypher(anyString(), any());
    }

    @Test
    void unitAndFormatMustAgree() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/custom/columns/1")).put("format", "percent");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("unit_format");
        assertThat(r.errors().get(0).message()).contains("percentPoints");
    }

    @Test
    void ratioFormatOnZeroToHundredValuesIsCaughtLive() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/custom/columns/1")).put("unit", "ratio").put("format", "percent");
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("unit_scale");
    }

    @Test
    void parametersMustBeDeclaredAndTheWallClockIsRefused() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/custom")).put("cypher", "MATCH (c:Covenant)-[:FOR_CLIENT]->(:Client {clientId: $clientId}) "
                + "WHERE c.lastTestDate >= datetime($from) AND c.lastTestDate < datetime() SET c.seen = true "
                + "RETURN c.name AS name, c.headroomPct AS headroomPct, c.lastTestDate AS lastTestDate");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("params", "wall_clock", "read_only");
        assertThat(r.errors()).anyMatch(e -> e.message().contains("datetime($asOfDate)"));
    }

    @Test
    void labelsMustExistAndBeData() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/custom")).put("cypher", "MATCH (c:Covenants) MATCH (m:_Meta) RETURN c.name AS name, c.headroomPct AS headroomPct, m.asOfDate AS lastTestDate");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).anyMatch(e -> e.code().equals("labels") && e.message().contains("no label 'Covenants'"))
                .anyMatch(e -> e.code().equals("labels") && e.message().contains("metadata"));
    }

    @Test
    void viewRolesMustFitThePrimitiveAndColumns() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"bar\",\"x\":\"name\",\"y\":[\"nope\"],\"value\":\"name\"}"));
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).extracting(ValidationReport.Issue::path).contains("view.value", "view.y");
    }

    @Test
    void designIntentsAreCheckedAgainstThePrimitiveAndUnits() throws Exception {
        ObjectNode s = spec();
        // line offers emphasis latest|none only; a percent does not add up to a total; headroom (percent) vs a date reference
        s.set("view", mapper.readTree("{\"primitive\":\"line\",\"x\":\"lastTestDate\",\"y\":[\"headroomPct\"],"
                + "\"emphasis\":\"max\",\"headline\":\"total\",\"warnAt\":0.5}"));
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).extracting(ValidationReport.Issue::path).contains("view.emphasis", "view.headline", "view.warnAt");
    }

    @Test
    void aMeterNeedsARatioOrAReferenceInTheSameUnit() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"meter\",\"value\":\"headroomPct\"}"));
        assertThat(validator.validate(s, "default", null, Map.of("clientId", "CL-0001")).errors())
                .extracting(ValidationReport.Issue::path).contains("view.reference");
        ((ArrayNode) s.at("/data/custom/columns")).add(mapper.readTree("{\"name\":\"limitUsd\",\"type\":\"number\",\"unit\":\"USD\",\"format\":\"currency\",\"label\":\"Limit\"}"));
        s.set("view", mapper.readTree("{\"primitive\":\"meter\",\"value\":\"headroomPct\",\"reference\":\"limitUsd\"}"));
        assertThat(validator.validate(s, "default", null, Map.of("clientId", "CL-0001")).errors())
                .extracting(ValidationReport.Issue::message).anyMatch(m -> m.contains("same scale"));
    }

    @Test
    void aMetricWithXIsAShortAscendingSeries() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"metric\",\"value\":\"headroomPct\",\"x\":\"lastTestDate\",\"polarity\":\"higherIsBetter\"}"));
        returns("""
                [{"name":"a","headroomPct":22.5,"lastTestDate":"2026-09-30T00:00:00.000Z"},
                 {"name":"b","headroomPct":5.9,"lastTestDate":"2026-08-31T00:00:00.000Z"}]""");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        assertThat(r.warnings()).extracting(ValidationReport.Issue::code).contains("order");
    }

    @Test
    void compositionPartsAddUpAndAreNotNegative() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"composition\",\"x\":\"name\",\"y\":[\"headroomPct\"]}"));
        assertThat(validator.validate(s, "default", null, Map.of("clientId", "CL-0001")).errors())
                .extracting(ValidationReport.Issue::path).contains("view.y");
        ((ObjectNode) s.at("/data/custom/columns/1")).put("unit", "count").put("format", "integer");
        returns("[{\"name\":\"a\",\"headroomPct\":3,\"lastTestDate\":\"2026-09-30T00:00:00.000Z\"},"
                + "{\"name\":\"b\",\"headroomPct\":-1,\"lastTestDate\":\"2026-09-30T00:00:00.000Z\"}]");
        assertThat(errors(validator.validate(s, "default", null, Map.of("clientId", "CL-0001")))).containsExactly("negative_part");
    }

    @Test
    void thePageMustSupplyTheParameters() throws Exception {
        ValidationReport r = validator.validate(spec(), "default", "command-center", null);
        assertThat(errors(r)).containsExactly("page_param");
        assertThat(r.errors().get(0).message()).contains("clientId");
    }

    @Test
    void emptyResultsExplainTheLikelyCause() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"metric\",\"value\":\"headroomPct\"}"));
        returns("[]");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).containsExactly("empty");
        assertThat(r.errors().get(0).message()).contains("datetime($asOfDate)");
    }

    @Test
    void truncationMissingColumnsAndWrongTypesAreErrors() throws Exception {
        returns("""
                [{"name":"A","headroomPct":"22.5","lastTestDate":"2026-09-30"},
                 {"name":"B","headroomPct":5,"lastTestDate":"2026-09-30T00:00:00.000Z"}]""", "config.maxDocumentsPerQuery");
        ValidationReport r = validator.validate(spec(), "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("truncated", "type");
        returns("[{\"name\":\"A\"}]");
        r = validator.validate(spec(), "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("missing_column");
    }

    @Test
    void aMetricMustReturnExactlyOneRow() throws Exception {
        ObjectNode s = spec();
        s.set("view", mapper.readTree("{\"primitive\":\"metric\",\"value\":\"headroomPct\"}"));
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).containsExactly("rows");
        assertThat(r.errors().get(0).message()).contains("no grouping key");
    }

    @Test
    void sampleValuesComeFromThePageWhenNotGiven() throws Exception {
        Layout.PageParam p = layout.page("client").param("clientId");
        p.setCollection("clients");  // stored before the graph: read as the Client label
        p.setValueField("clientId");
        p.setLabelField("name");
        when(source.find(eq("Client"), any(), any(), any(), any()))
                .thenReturn(new SourceReader.Result(mapper.readTree("[{\"clientId\":\"CL-0007\",\"name\":\"Acme\"}]"), 1, 1, List.of()));
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(spec(), "default", "client", null);
        assertThat(r.ok()).isTrue();
        assertThat(r.sampleParams()).containsEntry("clientId", "CL-0007");
    }

    // ------------------------------------------------------------------ narrative (insights)
    private ObjectNode insight(String statements) throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"id":"revenue-momentum","specVersion":2,"title":"Revenue momentum","description":"Revenue over the last 12 closed months.",
                 "params":[{"name":"clientId","type":"string"}],
                 "data":{"custom":{
                   "cypher":"MATCH (r:RevenueMonth) WHERE r.clientId = $clientId RETURN sum(r.totalUsd) AS revenueT12mUsd, 0.0 AS revenueYoY",
                   "columns":[{"name":"revenueT12mUsd","type":"number","unit":"USD","format":"currency","label":"Revenue (T12M)"},
                              {"name":"revenueYoY","type":"number","unit":"ratio","format":"percent","label":"Change"}]}},
                 "view":{"primitive":"narrative","figures":["revenueT12mUsd"],"statements":%s}}""".formatted(statements));
    }

    @Test
    void anInsightRendersItsStatementsOnTheSample() throws Exception {
        returns("[{\"revenueT12mUsd\":4250000,\"revenueYoY\":0.124}]");
        ValidationReport r = validator.validate(insight("""
                [{"text":"Revenue over the last 12 months is {revenueT12mUsd}."},
                 {"when":{"column":"revenueYoY","op":">","value":0},"text":"Up {revenueYoY} on the year before.","tone":"good"},
                 {"when":{"column":"revenueYoY","op":"<","value":0},"text":"Down {revenueYoY}.","tone":"bad"}]"""), "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.ok()).as(r.errors().toString()).isTrue();
        assertThat(r.preview().narrative().lines()).extracting(NarrativeRenderer.Line::text)
                .containsExactly("Revenue over the last 12 months is $4.3M.", "Up 12% on the year before.");
        assertThat(r.preview().narrative().figures()).extracting(NarrativeRenderer.Figure::text).containsExactly("$4.3M");
    }

    @Test
    void anInsightsSlotsAndConditionsMustNameItsColumns() throws Exception {
        returns("[{\"revenueT12mUsd\":4250000,\"revenueYoY\":0.124}]");
        ValidationReport r = validator.validate(insight("""
                [{"text":"Revenue is {revenue} and {revenueT12mUsd:shiny}."},
                 {"when":{"column":"growth","op":">","value":0},"text":"Up.","tone":"good"}]"""), "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.ok()).isFalse();
        assertThat(r.errors()).extracting(ValidationReport.Issue::message).anyMatch(m -> m.contains("{revenue} names 'revenue'"))
                .anyMatch(m -> m.contains("asks for format 'shiny'")).anyMatch(m -> m.contains("names 'growth'"));
        // a statement list is narrative-only
        ObjectNode table = spec();
        ((ObjectNode) table.get("view")).putArray("statements").addObject().put("text", "Hello");
        assertThat(validator.validate(table, "default", "client", Map.of("clientId", "CL-0001")).errors())
                .extracting(ValidationReport.Issue::path).contains("view.statements");
    }

    @Test
    void anInsightThatSaysNothingForTheSampleIsRefused() throws Exception {
        returns("[{\"revenueT12mUsd\":4250000,\"revenueYoY\":null}]");
        ValidationReport r = validator.validate(insight("""
                [{"when":{"column":"revenueYoY","op":">","value":0},"text":"Up {revenueYoY}.","tone":"good"}]"""), "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).extracting(ValidationReport.Issue::code).contains("narrative");
    }
}
