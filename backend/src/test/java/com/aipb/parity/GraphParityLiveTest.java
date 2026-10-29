package com.aipb.parity;

import com.aipb.semantic.QueryContext;
import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.ResultSet;
import com.aipb.semantic.SemanticModel;
import com.aipb.semantic.SemanticQuery;
import com.aipb.source.GraphSource;
import com.aipb.support.LiveSources;
import com.aipb.widget.SpecMigrator;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity with MongoDB: every starter widget for every client and the book, and every battery query, run on the graph
 * (planner, Cypher compiler, driver - as pages read) must return what the MongoDB stack returned for the same data
 * (graph-parity/, captured on the Mongo line at the pinned BANK_AS_OF by CaptureParityFixturesTest, commit 16fe8d7).
 * Rows match as multisets, numbers within 1e-6, dates by instant; record lists also in order. Needs the running stack,
 * seeded at the fixtures' asOfDate: {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
class GraphParityLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphSource graph = LiveSources.graph(mapper);
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);
    private final QueryExecutor executor = new QueryExecutor(sm, graph);
    private final WidgetDataService data = new WidgetDataService(graph, null, mapper, executor);

    @Test
    void theGraphIsSeededAtTheFixturesDate() throws Exception {
        assertThat(graph.asOfDate().substring(0, 10)).isEqualTo(read("starters.json").path("asOfDate").asText().substring(0, 10));
    }

    @TestFactory
    Stream<DynamicTest> everyStarterMatchesMongoForEveryClientAndTheBook() throws Exception {
        JsonNode fixtures = read("starters.json");
        Map<String, List<JsonNode>> byStarter = new TreeMap<>();
        fixtures.path("runs").forEach(r -> byStarter.computeIfAbsent(r.path("starter").asText(), k -> new ArrayList<>()).add(r));
        assertThat(byStarter).hasSize(12);
        return byStarter.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey() + " / " + e.getValue().size() + " runs", () -> {
            WidgetSpec spec = SpecMigrator.read(mapper, mapper.readTree(new ClassPathResource("widgets/starter/" + e.getKey() + ".json").getInputStream()));
            boolean ordered = spec.data().query().records();
            List<String> failures = new ArrayList<>();
            for (JsonNode run : e.getValue()) {
                String client = run.path("clientId").asText(null);
                WidgetDataService.DataResult r = data.execute(spec, client == null ? Map.of() : Map.of("clientId", client));
                assertThat(r.truncated()).isFalse();
                ParityFixtures.rowsMatch(run.path("rows"), r.rows(), ordered).ifPresent(d -> failures.add(client + ": " + d));
            }
            assertThat(failures).as(e.getKey()).isEmpty();
        }));
    }

    @TestFactory
    Stream<DynamicTest> everyBatteryQueryMatchesMongo() throws Exception {
        JsonNode fixtures = read("queries.json");
        java.time.LocalDate asOf = ParityFixtures.day(fixtures.path("asOfDate").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode run : fixtures.path("runs")) {
            String client = run.path("clientId").asText(null);
            tests.add(DynamicTest.dynamicTest(run.path("name").asText() + (client == null ? "" : " / " + client), () -> {
                SemanticQuery q = mapper.treeToValue(run.path("query"), SemanticQuery.class);
                if (run.has("error")) {
                    assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> executor.execute(q, new QueryContext(asOf, client)))).isNotNull();
                    return;
                }
                ResultSet r = executor.execute(q, new QueryContext(asOf, client));
                assertThat(r.truncated()).isFalse();
                assertThat(r.hasMore()).isEqualTo(run.path("hasMore").asBoolean());
                ParityFixtures.rowsMatch(run.path("rows"), r.rows(), q.records()).ifPresent(d -> { throw new AssertionError(d); });
            }));
        }
        assertThat(tests).hasSizeGreaterThan(200);
        return tests.stream();
    }

    private JsonNode read(String name) throws Exception {
        return mapper.readTree(new ClassPathResource(ParityFixtures.DIR + name).getInputStream());
    }
}
