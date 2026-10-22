package com.aipb.semantic;

import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import static com.aipb.semantic.PipelineCompilerTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Assembling results from canned source rows: merging, comparisons, filled periods, derived metrics, records. */
class QueryExecutorTest {

    /** A source that answers each aggregate with whatever the responder returns for (collection, pipeline). */
    static final class FakeSource extends SourceData {
        final List<String> calls = new ArrayList<>();
        private final BiFunction<String, ArrayNode, String> responder;

        FakeSource(BiFunction<String, ArrayNode, String> responder) {
            super(null, "bank");
            this.responder = responder;
        }

        @Override
        public Result aggregate(String collection, ArrayNode pipeline) {
            calls.add(collection + " " + pipeline);
            try {
                JsonNode docs = MAPPER.readTree(responder.apply(collection, pipeline));
                return new Result(docs, docs.size(), docs.size(), List.of());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String asOfDate() { return "2026-10-16T00:00:00Z"; }
    }

    private ResultSet run(String query, String entity, BiFunction<String, ArrayNode, String> responder) {
        return new QueryExecutor(SM, new FakeSource(responder)).execute(q(query), entity);
    }

    @Test
    void aTrendHasEveryPeriodEvenWithoutRevenue() {
        // two of the eight quarters have revenue; the rest must show as 0, in order
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"quarter\"], \"window\": {\"type\": \"lastClosed\", \"unit\": \"quarter\", \"n\": 8}}",
                "CL-0001", (c, p) -> "[{\"quarter\": \"2026-04-01T00:00:00.000Z\", \"m0\": 500}, {\"quarter\": \"2025-01-01T00:00:00.000Z\", \"m0\": 200}]");
        assertThat(r.rows()).hasSize(8);
        assertThat(r.rows().get(0).get("quarter").asText()).isEqualTo("2024-10-01T00:00:00Z");
        assertThat(r.rows().get(7).get("quarter").asText()).isEqualTo("2026-07-01T00:00:00Z");
        assertThat(r.rows().get(1).get("revenueUsd").asDouble()).isEqualTo(200);
        assertThat(r.rows().get(6).get("revenueUsd").asDouble()).isEqualTo(500);
        assertThat(r.rows().get(7).get("revenueUsd").asDouble()).isZero();
        assertThat(r.column("quarter").grain()).isEqualTo("quarter");
        assertThat(r.column("revenueUsd").format()).isEqualTo("currency");
    }

    @Test
    void priorYearPeriodsLineUpWithThisYears() {
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"month\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}",
                "CL-0001", (c, p) -> p.toString().contains("2025-01-01")
                        ? "[{\"month\": \"2025-03-01T00:00:00Z\", \"m0\": 70}]"
                        : "[{\"month\": \"2026-03-01T00:00:00Z\", \"m0\": 100}]");
        assertThat(r.rows()).hasSize(9);
        JsonNode march = r.rows().get(2);
        assertThat(march.get("month").asText()).isEqualTo("2026-03-01T00:00:00Z");
        assertThat(march.get("revenueUsd").asDouble()).isEqualTo(100);
        assertThat(march.get("revenueUsdPrior").asDouble()).isEqualTo(70);
        assertThat(r.column("revenueUsdPrior").label()).isEqualTo("Revenue (prior year)");
    }

    @Test
    void aRatioWithNothingToDivideByIsUnknownNotZero() {
        ResultSet none = run("{\"metrics\": [\"exposureUsd\", \"utilization\"]}", "CL-0009", (c, p) -> "[]");
        assertThat(none.rows()).hasSize(1);
        assertThat(none.rows().get(0).get("exposureUsd").asDouble()).isZero();
        assertThat(none.rows().get(0).get("utilization").isNull()).isTrue();

        ResultSet some = run("{\"metrics\": [\"utilization\"]}", "CL-0001", (c, p) -> "[{\"m0\": 25, \"m1\": 100}]");
        assertThat(some.rows().get(0).get("utilization").asDouble()).isEqualTo(0.25);
    }

    @Test
    void crossModelMetricsMergeOnTheSameRows() {
        ResultSet r = run("{\"metrics\": [\"shareOfWallet\"], \"dimensions\": [\"client\"]}", null, (c, p) -> c.equals("clients")
                ? "[{\"client\": \"CL-1\", \"clientName\": \"One\", \"m0\": 1000}, {\"client\": \"CL-2\", \"clientName\": \"Two\", \"m0\": 400}]"
                : "[{\"client\": \"CL-1\", \"clientName\": \"One\", \"m0\": 100}, {\"client\": \"CL-2\", \"clientName\": \"Two\", \"m0\": 200}]");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).get("client").asText()).isEqualTo("CL-2"); // sorted by the metric, highest first
        assertThat(r.rows().get(0).get("shareOfWallet").asDouble()).isEqualTo(0.5);
        assertThat(r.rows().get(1).get("clientName").asText()).isEqualTo("One");
        assertThat(r.column("client").hidden()).isTrue();
    }

    @Test
    void sortAndLimitApplyAfterMerging() {
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"lob\"], \"sort\": [{\"by\": \"lob\", \"dir\": \"asc\"}], \"limit\": 2}",
                null, (c, p) -> "[{\"lob\": \"Global Markets\", \"m0\": 1}, {\"lob\": \"Capital Markets\", \"m0\": 2}, {\"lob\": \"Corporate Banking\", \"m0\": 3}]");
        assertThat(r.rows()).extracting(n -> n.get("lob").asText()).containsExactly("Capital Markets", "Corporate Banking");
        assertThat(r.hasMore()).isTrue();
    }

    @Test
    void recordsReportMoreRowsThanTheLimit() {
        ResultSet r = run("{\"kind\": \"records\", \"model\": \"contacts\", \"fields\": [\"name\", \"lastContactDate\"], \"limit\": 2}",
                "CL-0001", (c, p) -> "[{\"name\": \"A\", \"lastContactDate\": \"2026-09-01T00:00:00.000Z\"}, {\"name\": \"B\"}, {\"name\": \"C\"}]");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.hasMore()).isTrue();
        assertThat(r.rows().get(0).get("lastContactDate").asText()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(r.rows().get(1).get("lastContactDate").isNull()).isTrue();
        // sorted by isPrimary (default) although not asked for: carried as a hidden column
        assertThat(r.column("isPrimary").hidden()).isTrue();
    }
}
