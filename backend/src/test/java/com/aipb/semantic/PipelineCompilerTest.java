package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * Planning and compiling representative queries. The compiled pipelines are snapshot-tested against
 * src/test/resources/semantic/compiled/*.json, so any change to how a metric is computed shows up as a reviewable diff.
 * After an intended change, regenerate with {@code mvn test -Dtest=PipelineCompilerTest -Daipb.snapshots.update=true}.
 */
class PipelineCompilerTest {

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
    Stream<DynamicTest> compiledPipelinesMatchSnapshots() {
        boolean update = Boolean.getBoolean("aipb.snapshots.update");
        return CASES.entrySet().stream().map(e -> DynamicTest.dynamicTest(e.getKey(), () -> {
            QueryPlanner.Plan plan = planner.plan(q((String) e.getValue()[0]), (QueryContext) e.getValue()[1]);
            ArrayNode compiled = MAPPER.createArrayNode();
            for (QueryPlanner.PhysicalQuery pq : plan.queries()) {
                compiled.addObject().put("purpose", pq.purpose()).put("collection", pq.model().collection())
                        .set("pipeline", PipelineCompiler.compile(pq));
            }
            Path file = SNAPSHOTS.resolve(e.getKey() + ".json");
            String actual = MAPPER.writeValueAsString(compiled) + "\n";
            if (update || !Files.exists(file)) {
                Files.createDirectories(SNAPSHOTS);
                Files.writeString(file, actual);
            }
            assertThat(actual).as("compiled pipelines for " + e.getKey() + " (regenerate with -Daipb.snapshots.update=true)")
                    .isEqualTo(Files.readString(file));
        }));
    }

    @Test
    void sharedSegmentsArePushedIntoTheMatchAndNoJoinIsAddedUnneeded() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"exposureUsd\", \"committedUsd\", \"utilization\"]}"), CLIENT);
        assertThat(plan.queries()).hasSize(1);
        JsonNode p = PipelineCompiler.compile(plan.queries().get(0));
        assertThat(p.get(0).path("$match").path("clientId").path("$eq").asText()).isEqualTo("CL-0001");
        assertThat(p.get(0).path("$match").path("productType").path("$eq").asText()).isEqualTo("credit");
        assertThat(p.toString()).doesNotContain("$lookup").doesNotContain("$cond");
    }

    @Test
    void measuresWithDifferentSegmentsShareOnePassConditionally() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"exposureUsd\", \"depositsUsd\"]}"), CLIENT);
        assertThat(plan.queries()).hasSize(1);
        String p = PipelineCompiler.compile(plan.queries().get(0)).toString();
        assertThat(p).contains("$cond").contains("\"credit\"").contains("\"deposit\"");
    }

    @Test
    void clientAttributesGoThroughTheManyToOneJoin() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"client\"], \"filters\": [{\"on\": \"region\", \"op\": \"eq\", \"value\": \"EMEA\"}]}"), BOOK);
        JsonNode p = PipelineCompiler.compile(plan.queries().get(0));
        assertThat(p.toString()).contains("\"$lookup\"").contains("\"from\":\"clients\"").contains("\"_j_client.region\"").contains("\"$_j_client.name\"");
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
        assertThat(PipelineCompiler.compile(plan.queries().get(0)).toString()).doesNotContain("CL-0001");
    }
}
