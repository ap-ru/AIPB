package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Driver values and the MCP server's JSON become the same plain JSON, with the instants the MongoDB readers wrote. */
class GraphJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void driverValuesAndMcpJsonAgree() throws Exception {
        JsonNode fromDriver = GraphJson.of(Values.value(Map.of("openedDate", ZonedDateTime.of(2021, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                "drawnUsd", 178_400_000L, "n", 7L, "rate", 0.25, "tags", List.of("a", "b"),
                "evidence", "[{\"label\":\"Utilization\",\"value\":\"92%\"}]")), Set.of("evidence"));
        JsonNode fromMcp = GraphJson.simplify(mapper.readTree("{\"openedDate\": \"2021-06-01T00:00:00.000000000+00:00\", \"drawnUsd\": 178400000,"
                + " \"n\": 7, \"rate\": 0.25, \"tags\": [\"a\", \"b\"], \"evidence\": \"[{\\\"label\\\":\\\"Utilization\\\",\\\"value\\\":\\\"92%\\\"}]\"}"), Set.of("evidence"));

        assertThat(fromDriver).isEqualTo(fromMcp);
        assertThat(fromDriver.path("openedDate").asText()).isEqualTo("2021-06-01T00:00:00.000Z");
        assertThat(fromDriver.path("drawnUsd").asLong()).isEqualTo(178_400_000L);
        assertThat(fromDriver.path("n").isInt()).isTrue();
        assertThat(fromDriver.path("evidence").get(0).path("value").asText()).isEqualTo("92%");
    }

    @Test
    void aDateIsMidnightUtcAndUnknownTextStaysText() throws Exception {
        assertThat(GraphJson.of(Values.value(LocalDate.of(2026, 10, 27)), Set.of()).asText()).isEqualTo("2026-10-27T00:00:00.000Z");
        assertThat(GraphJson.simplify(mapper.readTree("{\"a\": \"2026-10-27\", \"evidence\": \"not json\"}"), Set.of("evidence")).path("a").asText())
                .isEqualTo("2026-10-27");
    }
}
