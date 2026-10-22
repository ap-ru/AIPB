package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParamBinderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private WidgetSpec spec(String params, String pipeline) throws Exception {
        return SpecMigrator.read(mapper, mapper.readTree("""
                {"id":"t-spec","title":"t","description":"d","params":%s,
                 "data":{"collection":"c","pipeline":%s,"columns":[{"name":"x","type":"number","label":"X"}]},
                 "view":{"primitive":"metric","value":"x"}}""".formatted(params, pipeline)));
    }

    @Test
    void substitutesTypedValuesAndKeepsNulls() throws Exception {
        WidgetSpec s = spec("""
                [{"name":"clientId","type":"string"},{"name":"asOfDate","type":"date"},{"name":"n","type":"number"}]""", """
                [{"$match":{"clientId":{"$param":"clientId"},"month":{"$gte":{"$param":"asOfDate"}},"k":{"$param":"n"}}},
                 {"$group":{"_id":null,"t":{"$sum":1}}}]""");
        JsonNode p = ParamBinder.bind(s, Map.of("clientId", "CL-0001", "n", "2.5"), () -> "2026-10-16T00:00:00Z").pipeline();
        JsonNode m = p.get(0).get("$match");
        assertThat(m.get("clientId").asText()).isEqualTo("CL-0001");
        assertThat(m.get("month").get("$gte").get("$date").asText()).isEqualTo("2026-10-16T00:00:00Z");
        assertThat(m.get("k").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(p.get(1).get("$group").has("_id")).isTrue();
        assertThat(p.get(1).get("$group").get("_id").isNull()).isTrue();
    }

    @Test
    void plainDatesBecomeBsonDates() throws Exception {
        WidgetSpec s = spec("[{\"name\":\"from\",\"type\":\"date\"}]", "[{\"$match\":{\"d\":{\"$gte\":{\"$param\":\"from\"}}}}]");
        JsonNode p = ParamBinder.bind(s, Map.of("from", "2026-01-01"), () -> null).pipeline();
        assertThat(p.get(0).at("/$match/d/$gte/$date").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> ParamBinder.bind(s, Map.of("from", "Jan 1"), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a date");
    }

    @Test
    void aValueCannotChangeTheQueryShape() throws Exception {
        WidgetSpec s = spec("[{\"name\":\"clientId\",\"type\":\"string\"}]", "[{\"$match\":{\"clientId\":{\"$param\":\"clientId\"}}}]");
        JsonNode p = ParamBinder.bind(s, Map.of("clientId", "{\"$ne\": null}"), () -> null).pipeline();
        assertThat(p.get(0).get("$match").get("clientId").isTextual()).isTrue();
    }

    @Test
    void missingAndUndeclaredParamsAreNamed() throws Exception {
        WidgetSpec s = spec("[{\"name\":\"clientId\",\"type\":\"string\"}]", "[{\"$match\":{\"clientId\":{\"$param\":\"clientId\"}}}]");
        assertThatThrownBy(() -> ParamBinder.bind(s, Map.of(), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing parameter clientId");
        WidgetSpec undeclared = spec("[]", "[{\"$match\":{\"clientId\":{\"$param\":\"clientId\"}}}]");
        assertThatThrownBy(() -> ParamBinder.bind(undeclared, Map.of("clientId", "x"), () -> null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not declared");
        assertThat(ParamBinder.referenced(undeclared.custom().pipeline())).containsExactly("clientId");
    }
}
