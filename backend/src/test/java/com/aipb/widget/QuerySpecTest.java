package com.aipb.widget;

import com.aipb.model.Layout;
import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.ResultSet;
import com.aipb.semantic.SemanticModel;
import com.aipb.semantic.SemanticModelValidator;
import com.aipb.service.LayoutService;
import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Spec format 2: migration from format 1, query specs through the validator, and the design tokens they rely on. */
class QuerySpecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);
    private SourceData source;
    private WidgetSpecValidator validator;
    private WidgetDataService data;
    private final WidgetPrimitives primitives = new WidgetPrimitives(mapper);

    @BeforeEach
    void setUp() {
        source = mock(SourceData.class);
        when(source.collectionsCached()).thenReturn(List.of("_catalog", "holdings", "revenue_monthly"));
        when(source.asOfDate()).thenReturn("2026-10-16T00:00:00.000Z");
        LayoutService layouts = mock(LayoutService.class);
        Layout layout = new Layout();
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
        data = new WidgetDataService(source, mock(WidgetDefinitionRepository.class), mapper, new QueryExecutor(sm, source));
        validator = new WidgetSpecValidator(primitives, data, source, layouts, mapper);
    }

    private ObjectNode json(String s) throws Exception { return (ObjectNode) mapper.readTree(s); }

    private void returns(String rows) throws Exception {
        JsonNode r = mapper.readTree(rows);
        when(source.aggregate(anyString(), any(ArrayNode.class))).thenReturn(new SourceData.Result(r, r.size(), r.size(), List.of()));
    }

    private static final String EXPOSURE = """
            {"id": "exposure", "specVersion": 2, "title": "Credit exposure", "description": "Drawn against committed.",
             "data": {"query": {"metrics": ["exposureUsd", "committedUsd", "utilization"], "scope": "entity"},
                      "columns": [{"name": "exposureUsd", "label": "Drawn"}]},
             "view": {"primitive": "meter", "value": "exposureUsd", "reference": "committedUsd", "secondary": "utilization",
                      "polarity": "lowerIsBetter", "warnAt": 0.8}}""";

    private List<String> codes(List<ValidationReport.Issue> issues) { return issues.stream().map(ValidationReport.Issue::code).toList(); }

    // ------------------------------------------------------------------ migration
    @Test
    void formatOneIsReadAsCustomDataLosslessly() throws Exception {
        ObjectNode v1 = json("""
                {"id": "xyz-spec", "title": "t", "description": "d", "params": [{"name": "clientId", "type": "string"}],
                 "data": {"collection": "holdings", "pipeline": [{"$match": {"clientId": {"$param": "clientId"}}}],
                          "columns": [{"name": "n", "type": "number", "label": "N"}]},
                 "view": {"primitive": "metric", "value": "n"}}""");
        JsonNode v2 = SpecMigrator.migrate(v1);
        assertThat(v2.path("specVersion").asInt()).isEqualTo(2);
        assertThat(v2.path("data").path("custom").path("collection").asText()).isEqualTo("holdings");
        assertThat(v2.path("data").path("custom").path("pipeline")).isEqualTo(v1.path("data").path("pipeline"));
        assertThat(v2.path("data").has("collection")).isFalse();
        assertThat(v2.path("params")).isEqualTo(v1.path("params"));
        assertThat(v1.path("data").has("collection")).as("input untouched").isTrue();
        assertThat(SpecMigrator.migrate(v2)).as("idempotent").isEqualTo(v2);
        assertThat(primitives.schemaErrors(v2)).isEmpty();
    }

    // ------------------------------------------------------------------ validation of query specs
    @Test
    void aQuerySpecGetsItsColumnsFromTheSemanticLayer() throws Exception {
        returns("[{\"m0\": 40000000, \"m1\": 50000000}]");
        ValidationReport r = validator.validate(json(EXPOSURE), "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).isEmpty();
        assertThat(r.sampleParams()).containsEntry("clientId", "CL-0001");
        ResultSet.Column drawn = r.preview().columns().stream().filter(c -> c.name().equals("exposureUsd")).findFirst().orElseThrow();
        assertThat(drawn.label()).isEqualTo("Drawn");
        assertThat(drawn.format()).isEqualTo("currency");
        assertThat(drawn.lineage().sources()).containsExactly("holdings.drawnUsd");
        assertThat(r.preview().rows().get(0).get("utilization").asDouble()).isEqualTo(0.8);
    }

    @Test
    void whatTheSemanticLayerCannotAnswerIsReportedOnTheQuery() throws Exception {
        ObjectNode s = json(EXPOSURE);
        ((ArrayNode) ((ObjectNode) s.get("data").get("query")).withArray("metrics")).set(0, mapper.getNodeFactory().textNode("exposure"));
        ValidationReport r = validator.validate(s, "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).singleElement().satisfies(i -> {
            assertThat(i.code()).isEqualTo("query");
            assertThat(i.path()).isEqualTo("data.query.metrics[0]");
            assertThat(i.message()).contains("unknown metric 'exposure'");
        });
    }

    @Test
    void overridesOnlyRelabelAndTone() throws Exception {
        ObjectNode s = json(EXPOSURE);
        ((ObjectNode) s.get("data")).withArray("columns").addObject().put("name", "drawn").put("label", "x");
        s.putArray("params").addObject().put("name", "clientId").put("type", "string");
        ValidationReport r = validator.validate(s, "default", "client", Map.of("clientId", "CL-0001"));
        assertThat(r.errors()).extracting(ValidationReport.Issue::message)
                .anyMatch(m -> m.contains("'drawn', which the query does not return"))
                .anyMatch(m -> m.contains("remove params"));
        ObjectNode typed = json(EXPOSURE);
        ((ObjectNode) typed.get("data")).withArray("columns").addObject().put("name", "committedUsd").put("format", "integer");
        assertThat(codes(validator.validate(typed, "default", "client", Map.of("clientId", "CL-0001")).errors())).containsExactly("schema");
    }

    @Test
    void aClientWidgetCannotGoOnABookPageButAnInheritingOneCan() throws Exception {
        returns("[{\"m0\": 1, \"m1\": 2}]");
        assertThat(codes(validator.validate(json(EXPOSURE), "default", "command-center", null).errors())).contains("page_param");
        ObjectNode inherit = json(EXPOSURE);
        ((ObjectNode) inherit.get("data").get("query")).remove("scope");
        ValidationReport r = validator.validate(inherit, "default", "command-center", null);
        assertThat(r.errors()).isEmpty();
        assertThat(data.requiredParams(SpecMigrator.read(mapper, inherit))).isEmpty();
        assertThat(data.requiredParams(SpecMigrator.read(mapper, json(EXPOSURE)))).containsExactly("clientId");
    }

    @Test
    void aSeriesRoleTakesOneYAndAtMostFourSeries() throws Exception {
        ObjectNode s = json("""
                {"id": "rev", "specVersion": 2, "title": "Revenue", "description": "By month and LOB.",
                 "data": {"query": {"metrics": ["revenueUsd", "feeUsd"], "dimensions": ["month", "lob"], "window": {"type": "trailing", "unit": "month", "n": 2}}},
                 "view": {"primitive": "line", "x": "month", "y": ["revenueUsd", "feeUsd"], "series": "lob"}}""");
        assertThat(validator.validate(s, "default", "command-center", null).errors()).extracting(ValidationReport.Issue::message)
                .anyMatch(m -> m.contains("with series, give exactly one y column"));
        ((ArrayNode) ((ObjectNode) s.get("view")).withArray("y")).remove(1);
        StringBuilder rows = new StringBuilder("[");
        for (String lob : List.of("A", "B", "C", "D", "E")) for (String m : List.of("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z"))
            rows.append(rows.length() > 1 ? "," : "").append("{\"month\": \"").append(m).append("\", \"lob\": \"").append(lob).append("\", \"m0\": 1, \"m1\": 1}");
        returns(rows.append("]").toString());
        ValidationReport r = validator.validate(s, "default", "command-center", null);
        assertThat(r.errors()).extracting(ValidationReport.Issue::message).anyMatch(m -> m.contains("at most 4 series"));
    }

    // ------------------------------------------------------------------ tokens shared by the layers
    @Test
    void everySemanticUnitAndDefaultFormatIsADesignSystemToken() {
        Set<String> units = new HashSet<>();
        primitives.catalog().path("units").fieldNames().forEachRemaining(units::add);
        Set<String> formats = new HashSet<>();
        primitives.catalog().path("formats").fieldNames().forEachRemaining(formats::add);
        assertThat(units).containsAll(SemanticModelValidator.UNITS);
        for (String u : SemanticModelValidator.UNITS)
            for (String t : List.of("number", "string", "date", "boolean"))
                assertThat(formats).contains(ResultSet.defaultFormat(t, u, false));
        assertThat(formats).contains(ResultSet.defaultFormat("string", null, true));
    }

    @Test
    void everyStarterIsASemanticQueryThatPlans() throws Exception {
        int n = 0;
        for (var r : new PathMatchingResourcePatternResolver().getResources("classpath:widgets/starter/*.json")) {
            try (InputStream in = r.getInputStream()) {
                JsonNode raw = mapper.readTree(in);
                assertThat(primitives.schemaErrors(raw)).as(r.getFilename()).isEmpty();
                WidgetSpec s = SpecMigrator.read(mapper, raw);
                assertThat(s.isQuery()).as(r.getFilename()).isTrue();
                List<ResultSet.Column> cols = data.columns(s);
                Set<String> names = new HashSet<>();
                cols.forEach(c -> names.add(c.name()));
                JsonNode view = mapper.valueToTree(s.view());
                view.fields().forEachRemaining(e -> {
                    if (Set.of("primitive", "orientation", "stacked", "emphasis", "polarity", "headline", "warnAt").contains(e.getKey())) return;
                    (e.getValue().isArray() ? e.getValue() : mapper.createArrayNode().add(e.getValue())).forEach(c ->
                            assertThat(names).as(r.getFilename() + " view." + e.getKey()).contains(c.asText()));
                });
                n++;
            }
        }
        assertThat(n).isEqualTo(12);
    }
}
