package com.aipb.source;

import com.aipb.parity.ParityFixtures;
import com.aipb.semantic.Condition;
import com.aipb.support.LiveSources;
import com.aipb.widget.ParamBinder;
import com.aipb.widget.SpecMigrator;
import com.aipb.widget.WidgetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two graph readers against the running stack: they agree, they cap, and nothing writes - not through the driver,
 * not through MCP, and not even straight at the MCP server past the backend's gate (the database is read-only).
 * Also: each condition operator treats a missing property as MongoDB did, and the assistant's Cypher worked examples
 * return what their starters returned on MongoDB. {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
class GraphSourceLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GraphSource graph = LiveSources.graph(mapper);
    private final GraphMcpSource mcp = LiveSources.mcp(mapper, graph);

    @Test
    void bothReadersReturnTheSameNodes() {
        Map<String, Object> filter = Map.of("clientId", "CL-0001");
        Map<String, Object> sort = Map.of("holdingId", 1);
        JsonNode direct = graph.find("Holding", filter, null, sort, 50).documents();
        assertThat(direct.size()).isGreaterThan(1);
        assertThat(direct).isEqualTo(mcp.find("Holding", filter, null, sort, 50).documents());
        assertThat(direct.get(0).path("openedDate").asText()).matches("\\d{4}-\\d\\d-\\d\\dT00:00:00\\.000Z");
        // a JSON-stored property comes back as records, from either reader
        JsonNode client = graph.find("Client", Map.of("clientId", "CL-0001"), List.of("coverageTeam"), null, 1).documents().get(0);
        assertThat(client.path("coverageTeam").isArray()).isTrue();
        assertThat(mcp.find("Client", Map.of("clientId", "CL-0001"), List.of("coverageTeam"), null, 1).documents().get(0)).isEqualTo(client);
    }

    @Test
    void aLongResultIsCappedWithItsFullCount() {
        SourceReader.Result r = graph.find("RevenueMonth", null, List.of("month"), null, null);
        assertThat(r.truncated()).isTrue();
        assertThat(r.count()).isEqualTo(1000);
        assertThat(r.total()).isEqualTo(6148);
        assertThat(graph.find("RevenueMonth", null, List.of("month"), null, 10).truncated()).as("a limit of one's own is not a cut").isFalse();
    }

    @Test
    void nothingWrites() {
        for (SourceReader reader : List.of(graph, mcp)) {
            assertThatThrownBy(() -> reader.cypher("CREATE (x:Hack) RETURN x", Map.of())).isInstanceOf(SourceException.class).hasMessageContaining("read-only");
            assertThatThrownBy(() -> reader.cypher("CREATE INDEX hack IF NOT EXISTS FOR (n:Hack) ON (n.x)", Map.of())).isInstanceOf(SourceException.class);
        }
        // hidden from the text check, caught by EXPLAIN
        assertThatThrownBy(() -> graph.explain("MATCH (c:Client) WITH c LIMIT 1 CALL (c) { WITH c SET c.x = 1 } RETURN c", Map.of()))
                .isInstanceOf(SourceException.class);
        // straight at the MCP server, past the backend's gate: the database itself refuses data and schema writes
        McpClient raw = new McpClient(mapper, System.getProperty("aipb.mcp-url", "http://localhost:3001/mcp/"), 30);
        assertThatThrownBy(() -> raw.callTool("read_neo4j_cypher", Map.of("query", "CREATE INDEX hack IF NOT EXISTS FOR (n:Hack) ON (n.x)")))
                .isInstanceOf(SourceException.class).hasMessageContaining("read-only");
        assertThatThrownBy(() -> raw.callTool("read_neo4j_cypher", Map.of("query", "CREATE (x:Hack) RETURN x"))).isInstanceOf(SourceException.class);
        assertThat(graph.cypher("MATCH (n:Hack) RETURN count(n) AS n", Map.of()).documents().get(0).path("n").asInt()).isZero();
    }

    @Test
    void conditionsTreatAMissingPropertyAsMongoDid() {
        // MongoDB: a missing field is "not equal" and "not in"; every other test fails on it
        Map<String, Boolean> onMissing = Map.of("eq", false, "ne", true, "in", false, "nin", true, "gt", false, "gte", false, "lt", false, "lte", false);
        Map<String, Boolean> onFive = Map.of("eq", false, "ne", true, "in", false, "nin", true, "gt", true, "gte", true, "lt", false, "lte", false);
        for (String op : Condition.OPS) {
            JsonNode v = op.equals("in") || op.equals("nin") ? mapper.valueToTree(List.of(3)) : IntNode.valueOf(3);
            String pred = new Condition("x", op, v).toCypher("r.x", "p");
            JsonNode rows = graph.cypher("UNWIND [{x: 5}, {y: 1}] AS r RETURN r.y IS NOT NULL AS missing, coalesce(" + pred + ", false) AS holds",
                    Map.of("p", mapper.convertValue(v, Object.class))).documents();
            for (JsonNode row : rows) {
                boolean want = row.path("missing").asBoolean() ? onMissing.get(op) : onFive.get(op);
                assertThat(row.path("holds").asBoolean()).as(op + (row.path("missing").asBoolean() ? " on a missing value" : " on 5")).isEqualTo(want);
            }
        }
    }

    @Test
    void theWorkedExamplesReturnWhatTheirStartersDidOnMongo() throws Exception {
        JsonNode fixtures = mapper.readTree(new ClassPathResource(ParityFixtures.DIR + "starters.json").getInputStream());
        Map<String, String> sameColumns = Map.of("client-exposure", "", "client-revenue-by-lob", "", "client-active-signals", "");
        for (String id : sameColumns.keySet()) {
            WidgetSpec ex = SpecMigrator.read(mapper, mapper.readTree(new ClassPathResource("assistant/examples/" + id + ".json").getInputStream()));
            List<String> failures = new ArrayList<>();
            for (JsonNode run : fixtures.path("runs")) {
                if (!run.path("starter").asText().equals(id)) continue;
                String client = run.path("clientId").asText();
                ParamBinder.Bound b = ParamBinder.bind(ex, Map.of("clientId", client), graph::asOfDate);
                JsonNode rows = graph.cypher(ex.custom().cypher(), b.params()).documents();
                JsonNode want = run.path("rows");
                if (id.equals("client-exposure") && want.isEmpty()) continue; // no credit: the example returns zeros, the starter no row
                if (id.equals("client-exposure") && want.get(0).path("utilization").isNull()) continue; // no committed limit: 0 vs unknown
                if (id.equals("client-active-signals")) { // the starter shows its title and subtitle; compare those
                    rows = project(rows, "title", "subtitle", "severity");
                    want = project(want, "title", "subtitle", "severity");
                }
                ParityFixtures.rowsMatch(want, rows, false).ifPresent(d -> failures.add(client + ": " + d));
            }
            assertThat(failures).as(id).isEmpty();
        }
    }

    private JsonNode project(JsonNode rows, String... names) {
        var out = mapper.createArrayNode();
        rows.forEach(r -> { var o = out.addObject(); for (String n : names) o.set(n, r.get(n)); });
        return out;
    }

    @Test
    void theSignalTrailFollowsTheGraphToTheProduct() throws Exception {
        var executor = new com.aipb.semantic.QueryExecutor(com.aipb.semantic.SemanticModel.fromClasspath(mapper), graph);
        // independently: each signal's product, following the relationships by hand
        Map<String, String> want = new HashMap<>();
        graph.cypher("""
                MATCH (s:Signal) WHERE s.clientId = $c
                OPTIONAL MATCH (s)-[:CONCERNS]->(x)
                OPTIONAL MATCH (x)-[:OF_PRODUCT|FOR_PRODUCT]->(p1:Product)
                OPTIONAL MATCH (x)<-[:HAS_COVENANT]-(:Holding)-[:OF_PRODUCT]->(p2:Product)
                RETURN s.signalId AS id, s.title AS title, coalesce(p1.name, p2.name) AS product""", Map.of("c", "CL-0017"))
                .documents().forEach(d -> want.put(d.path("title").asText(), d.path("product").asText(null)));
        assertThat(want).isNotEmpty();
        var r = executor.execute(mapper.readValue("{\"kind\": \"records\", \"model\": \"signals\", \"fields\": [\"title\", \"concerns\", \"trailProduct\"], \"limit\": 200}",
                com.aipb.semantic.SemanticQuery.class), "CL-0017");
        assertThat(r.rows().size()).isEqualTo(want.size());
        int traced = 0;
        for (JsonNode row : r.rows()) {
            assertThat(row.path("trailProduct").asText(null)).as(row.path("title").asText()).isEqualTo(want.get(row.path("title").asText()));
            if (!row.path("concerns").isNull()) traced++;
        }
        assertThat(traced).as("signals that trace to something").isPositive();
    }
}
