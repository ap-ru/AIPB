package com.aipb.widget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamBinderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WidgetSpec spec(String params, String cypher) throws Exception {
        ObjectNode s = (ObjectNode) mapper.readTree("""
                {"id":"t-spec","specVersion":2,"title":"t","description":"d","params":%s,
                 "data":{"custom":{"columns":[{"name":"x","type":"number","label":"X"}]}},
                 "view":{"primitive":"metric","value":"x"}}""".formatted(params));
        ((ObjectNode) s.at("/data/custom")).put("cypher", cypher);
        return SpecMigrator.read(mapper, s);
    }

    @Test
    void bindsTypedValuesAsDriverParameters() throws Exception {
        WidgetSpec s = spec("""
                [{"name":"clientId","type":"string"},{"name":"asOfDate","type":"date"},{"name":"n","type":"number"},{"name":"k","type":"number"}]""",
                "MATCH (r:RevenueMonth) WHERE r.clientId = $clientId AND r.month >= datetime($asOfDate) AND r.totalUsd > $n AND r.feeUsd > $k RETURN count(r) AS x");
        Map<String, Object> p = ParamBinder.bind(s, Map.of("clientId", "CL-0001", "n", "2.5", "k", "3"), () -> "2026-10-16T00:00:00Z").params();
        assertThat(p).containsEntry("clientId", "CL-0001").containsEntry("asOfDate", "2026-10-16T00:00:00Z").containsEntry("n", 2.5).containsEntry("k", 3L);
    }

    @Test
    void datesBecomeIsoInstantsAndAsOfDateIsBuiltIn() throws Exception {
        WidgetSpec s = spec("[{\"name\":\"from\",\"type\":\"date\"}]", "MATCH (r:RevenueMonth) WHERE r.month >= datetime($from) AND r.month < datetime($asOfDate) RETURN count(r) AS x");
        Map<String, Object> p = ParamBinder.bind(s, Map.of("from", "2026-01-01"), () -> "2026-10-16T00:00:00.000Z").params();
        assertThat(p).containsEntry("from", "2026-01-01T00:00:00Z").containsEntry("asOfDate", "2026-10-16T00:00:00Z");
        assertThatThrownBy(() -> ParamBinder.bind(s, Map.of("from", "Jan 1"), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a date");
    }

    @Test
    void aValueCannotChangeTheQueryShape() throws Exception {
        String cypher = "MATCH (h:Holding) WHERE h.clientId = $clientId RETURN count(h) AS x";
        WidgetSpec s = spec("[{\"name\":\"clientId\",\"type\":\"string\"}]", cypher);
        Map<String, Object> p = ParamBinder.bind(s, Map.of("clientId", "x' OR 1=1 //"), () -> null).params();
        assertThat(p.get("clientId")).isEqualTo("x' OR 1=1 //");
        assertThat(s.custom().cypher()).isEqualTo(cypher);
    }

    @Test
    void missingAndUndeclaredParamsAreNamed() throws Exception {
        WidgetSpec s = spec("[{\"name\":\"clientId\",\"type\":\"string\"}]", "MATCH (h:Holding) WHERE h.clientId = $clientId RETURN count(h) AS x");
        assertThatThrownBy(() -> ParamBinder.bind(s, Map.of(), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing parameter clientId");
        WidgetSpec undeclared = spec("[]", "MATCH (h:Holding) WHERE h.clientId = $clientId AND h.note <> '$notAParam' RETURN count(h) AS x");
        assertThatThrownBy(() -> ParamBinder.bind(undeclared, Map.of("clientId", "x"), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not declared");
        assertThat(ParamBinder.referenced(undeclared.custom().cypher())).containsExactly("clientId");
    }
}
