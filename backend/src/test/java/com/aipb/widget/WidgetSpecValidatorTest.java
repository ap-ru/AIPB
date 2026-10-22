package com.aipb.widget;

import com.aipb.model.Layout;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceData;
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
    private SourceData source;
    private WidgetSpecValidator validator;
    private Layout layout;

    @BeforeEach
    void setUp() {
        source = mock(SourceData.class);
        when(source.collectionsCached()).thenReturn(List.of("_catalog", "clients", "covenants", "revenue_monthly"));
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
                {"id":"client-covenants","title":"Covenants","description":"Covenants of the client.",
                 "params":[{"name":"clientId","type":"string"}],
                 "data":{"collection":"covenants",
                   "pipeline":[{"$match":{"clientId":{"$param":"clientId"}}},{"$project":{"_id":0,"name":1,"headroomPct":1,"lastTestDate":1}}],
                   "columns":[{"name":"name","type":"string","label":"Covenant"},
                              {"name":"headroomPct","type":"number","unit":"percent","format":"percentPoints","label":"Headroom"},
                              {"name":"lastTestDate","type":"date","unit":"date","format":"date","label":"Tested"}]},
                 "view":{"primitive":"table","columns":["name","headroomPct","lastTestDate"]}}""");
    }

    private void returns(String rowsJson, String... limits) throws Exception {
        JsonNode rows = mapper.readTree(rowsJson);
        when(source.aggregate(anyString(), any(ArrayNode.class)))
                .thenReturn(new SourceData.Result(rows, rows.size(), rows.size(), List.of(limits)));
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
        ArgumentCaptor<ArrayNode> sent = ArgumentCaptor.forClass(ArrayNode.class);
        verify(source).aggregate(eq("covenants"), sent.capture());
        assertThat(sent.getValue().at("/0/$match/clientId").asText()).isEqualTo("CL-0001");
    }

    @Test
    void schemaViolationsStopEverything() throws Exception {
        ObjectNode s = spec();
        s.remove("view");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).containsOnly("schema");
        verify(source, never()).aggregate(anyString(), any(ArrayNode.class));
    }

    @Test
    void unitAndFormatMustAgree() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/columns/1")).put("format", "percent");
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("unit_format");
        assertThat(r.errors().get(0).message()).contains("percentPoints");
    }

    @Test
    void ratioFormatOnZeroToHundredValuesIsCaughtLive() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/columns/1")).put("unit", "ratio").put("format", "percent");
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("unit_scale");
    }

    @Test
    void parametersMustBeDeclaredAndTheWallClockIsRefused() throws Exception {
        ObjectNode s = spec();
        ((ObjectNode) s.at("/data/pipeline/0/$match")).set("month", mapper.readTree("{\"$gte\":{\"$param\":\"from\"}}"));
        ((ArrayNode) s.at("/data/pipeline")).add(mapper.readTree("{\"$addFields\":{\"now\":\"$$NOW\"}}"));
        ((ArrayNode) s.at("/data/pipeline")).add(mapper.readTree("{\"$out\":\"x\"}"));
        ValidationReport r = validator.validate(s, "default", null, Map.of("clientId", "CL-0001"));
        assertThat(errors(r)).contains("params", "wall_clock", "write_stage");
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
        ((ArrayNode) s.at("/data/columns")).add(mapper.readTree("{\"name\":\"limitUsd\",\"type\":\"number\",\"unit\":\"USD\",\"format\":\"currency\",\"label\":\"Limit\"}"));
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
        ((ObjectNode) s.at("/data/columns/1")).put("unit", "count").put("format", "integer");
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
        assertThat(r.errors().get(0).message()).contains("plain string");
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
        assertThat(r.errors().get(0).message()).contains("_id: null");
    }

    @Test
    void sampleValuesComeFromThePageWhenNotGiven() throws Exception {
        Layout.PageParam p = layout.page("client").param("clientId");
        p.setCollection("clients");
        p.setValueField("clientId");
        p.setLabelField("name");
        when(source.find(eq("clients"), any(), any(), any(), any()))
                .thenReturn(new SourceData.Result(mapper.readTree("[{\"clientId\":\"CL-0007\",\"name\":\"Acme\"}]"), 1, 1, List.of()));
        returns(GOOD_ROWS);
        ValidationReport r = validator.validate(spec(), "default", "client", null);
        assertThat(r.ok()).isTrue();
        assertThat(r.sampleParams()).containsEntry("clientId", "CL-0007");
    }
}
