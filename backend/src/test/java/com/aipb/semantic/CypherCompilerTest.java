package com.aipb.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Planning and compiling representative queries. The compiled Cypher is snapshot-tested against
 * src/test/resources/semantic/compiled/*.cypher, so any change to how a metric is computed shows up as a reviewable
 * diff. After an intended change, regenerate with {@code mvn test -Dtest=CypherCompilerTest -Daipb.snapshots.update=true}.
 */
class CypherCompilerTest {

    static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    static final SemanticModel SM = SemanticModel.fromClasspath(MAPPER);
    static final LocalDate AS_OF = LocalDate.of(2026, 10, 16);
    static final QueryContext CLIENT = QueryContext.entity(AS_OF, "CL-0001");
    static final QueryContext BOOK = QueryContext.book(AS_OF);
    private static final Path SNAPSHOTS = Path.of("src/test/resources/semantic/compiled");

    private final QueryPlanner planner = new QueryPlanner(SM);

    static SemanticQuery q(String json) {
        try { return MAPPER.readValue(json, SemanticQuery.class); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }

    /** Named representative queries: client KPIs, trends, book rankings, cross-model metrics, records. */
    static final Map<String, Object[]> CASES = new LinkedHashMap<>();
    static {
        CASES.put("revenue-ytd-vs-prior-year", new Object[]{"{\"metrics\": [\"revenueUsd\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}", CLIENT});
        CASES.put("exposure-utilization", new Object[]{"{\"metrics\": [\"exposureUsd\", \"committedUsd\", \"utilization\"]}", CLIENT});
        CASES.put("deposits-accounts", new Object[]{"{\"metrics\": [\"depositsUsd\", \"depositAccounts\"]}", CLIENT});
        CASES.put("revenue-by-quarter", new Object[]{"{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"quarter\"], \"window\": {\"type\": \"lastClosed\", \"unit\": \"quarter\", \"n\": 8}}", CLIENT});
        CASES.put("revenue-by-lob-t12m", new Object[]{"{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"lob\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 12}}", CLIENT});
        CASES.put("book-revenue-by-month-and-lob", new Object[]{"{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"month\", \"lob\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 24}}", BOOK});
        CASES.put("share-of-wallet", new Object[]{"{\"metrics\": [\"shareOfWallet\", \"walletGapUsd\"]}", CLIENT});
        CASES.put("top-emea-clients-by-revenue", new Object[]{"{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"client\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 12}, \"filters\": [{\"on\": \"region\", \"op\": \"eq\", \"value\": \"EMEA\"}], \"limit\": 10}", BOOK});
        CASES.put("pipeline-by-stage", new Object[]{"{\"metrics\": [\"pipelineUsd\", \"openDeals\", \"weightedPipelineUsd\"], \"dimensions\": [\"dealStage\"]}", CLIENT});
        CASES.put("revenue-yoy", new Object[]{"{\"metrics\": [\"revenueYoY\"], \"window\": {\"type\": \"ytd\"}}", CLIENT});
        CASES.put("win-rate", new Object[]{"{\"metrics\": [\"winRate\", \"productsHeld\"]}", BOOK});
        CASES.put("covenants-records", new Object[]{"{\"kind\": \"records\", \"model\": \"covenants\", \"fields\": [\"name\", \"facility\", \"actual\", \"threshold\", \"headroomPct\", \"status\"]}", CLIENT});
        CASES.put("active-signals-records", new Object[]{"{\"kind\": \"records\", \"model\": \"signals\", \"segments\": [\"active\"], \"fields\": [\"title\", \"subtitle\", \"severity\", \"detectedAt\"], \"limit\": 10}", CLIENT});
        CASES.put("maturing-holdings-records", new Object[]{"{\"kind\": \"records\", \"model\": \"holdings\", \"window\": {\"type\": \"next\", \"unit\": \"day\", \"n\": 90, \"on\": \"maturity\"}, \"fields\": [\"productName\", \"amountUsd\", \"maturityDate\"], \"sort\": [{\"by\": \"maturityDate\", \"dir\": \"asc\"}]}", BOOK});
    }

    @TestFactory
    Stream<DynamicTest> compiledQueriesMatchSnapshots() {
        boolean update = Boolean.getBoolean("aipb.snapshots.update");
        return CASES.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            QueryPlanner.Plan plan = planner.plan(q((String) e.getValue()[0]), (QueryContext) e.getValue()[1]);
            StringBuilder compiled = new StringBuilder();
            for (QueryPlanner.PhysicalQuery pq : plan.queries()) {
                CypherCompiler.CompiledQuery cq = CypherCompiler.compile(pq);
                compiled.append("// ").append(pq.purpose()).append(" (").append(pq.model().node()).append(")\n").append(cq.cypher())
                        .append("\n// params: ").append(new ObjectMapper().writeValueAsString(cq.params())).append("\n\n");
            }
            Path file = SNAPSHOTS.resolve(e.getKey() + ".cypher");
            String actual = compiled.toString();
            if (update || !Files.exists(file)) {
                Files.createDirectories(SNAPSHOTS);
                Files.writeString(file, actual);
            }
            assertThat(actual).as("compiled Cypher for " + e.getKey() + " (regenerate with -Daipb.snapshots.update=true)")
                    .isEqualTo(Files.readString(file));
        }));
    }

    @Test
    void sharedSegmentsArePushedIntoTheWhereAndNoJoinIsAddedUnneeded() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"exposureUsd\", \"committedUsd\", \"utilization\"]}"), CLIENT);
        assertThat(plan.queries()).hasSize(1);
        CypherCompiler.CompiledQuery c = CypherCompiler.compile(plan.queries().get(0));
        assertThat(c.cypher()).startsWith("MATCH (n:Holding)\nWHERE n.clientId = $p0 AND n.productType = $p1");
        assertThat(c.params()).containsEntry("p0", "CL-0001").containsEntry("p1", "credit");
        assertThat(c.cypher()).doesNotContain("OPTIONAL MATCH").doesNotContain("CASE");
    }

    @Test
    void measuresWithDifferentSegmentsShareOnePassConditionally() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"exposureUsd\", \"depositsUsd\"]}"), CLIENT);
        assertThat(plan.queries()).hasSize(1);
        CypherCompiler.CompiledQuery c = CypherCompiler.compile(plan.queries().get(0));
        assertThat(c.cypher()).contains("sum(CASE WHEN n.productType = $p1 THEN n.drawnUsd ELSE 0 END)")
                .contains("sum(CASE WHEN n.productType = $p2 THEN n.balanceUsd ELSE 0 END)");
        assertThat(c.params().values()).contains("credit", "deposit");
    }

    @Test
    void clientAttributesGoThroughTheManyToOneJoin() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"client\"], \"filters\": [{\"on\": \"region\", \"op\": \"eq\", \"value\": \"EMEA\"}]}"), BOOK);
        String c = CypherCompiler.compile(plan.queries().get(0)).cypher();
        assertThat(c).contains("OPTIONAL MATCH (n)-[:FOR_CLIENT]->(client:Client)").contains("WITH n, client\nWHERE client.region = $p0")
                .contains("n.clientId AS client, client.name AS clientName");
    }

    @Test
    void comparisonsAndCrossModelMetricsPlanOneQueryPerModelAndWindow() {
        assertThat(planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}"), CLIENT).queries()).hasSize(2);
        QueryPlanner.Plan sow = planner.plan(q("{\"metrics\": [\"shareOfWallet\", \"walletGapUsd\"]}"), CLIENT);
        assertThat(sow.queries()).extracting(pq -> pq.model().id()).containsExactlyInAnyOrder("revenue", "clients");
    }

    @Test
    void bookScopeNeverAppliesTheSelectedClient() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"scope\": \"book\"}"), CLIENT);
        CypherCompiler.CompiledQuery c = CypherCompiler.compile(plan.queries().get(0));
        assertThat(c.params().values()).doesNotContain("CL-0001");
        assertThat(c.cypher()).doesNotContain("clientId");
    }

    @Test
    void everyValueIsAParameterAndTheRangeIsComparedAsDatetimes() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"quarter\"], \"window\": {\"type\": \"lastClosed\", \"unit\": \"quarter\", \"n\": 8}, "
                + "\"filters\": [{\"on\": \"lob\", \"op\": \"in\", \"value\": [\"Capital Markets\"]}]}"), CLIENT);
        CypherCompiler.CompiledQuery c = CypherCompiler.compile(plan.queries().get(0));
        assertThat(c.cypher()).contains("n.month >= datetime($start) AND n.month < datetime($end)")
                .contains("datetime.truncate('quarter', n.month) AS quarter").doesNotContain("Capital Markets").doesNotContain("2024");
        assertThat(c.params()).containsEntry("start", "2024-10-01T00:00:00Z").containsEntry("end", "2026-10-01T00:00:00Z");
    }

    @Test
    void recordsSortMissingValuesAsMongoDidAndBreakTiesOnTheKey() {
        QueryPlanner.Plan plan = planner.plan(q("{\"kind\": \"records\", \"model\": \"holdings\", \"fields\": [\"productName\", \"maturityDate\"], "
                + "\"sort\": [{\"by\": \"maturityDate\", \"dir\": \"asc\"}], \"limit\": 5}"), CLIENT);
        String c = CypherCompiler.compile(plan.queries().get(0)).cypher();
        assertThat(c).contains("ORDER BY maturityDate IS NULL DESC, maturityDate, n.holdingId").endsWith("LIMIT $limit");
    }
}
