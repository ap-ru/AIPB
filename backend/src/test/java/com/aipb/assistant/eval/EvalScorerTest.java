package com.aipb.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The eval scorer on synthetic results: figures must match; display choices may differ. */
class EvalScorerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode j(String s) throws Exception { return mapper.readTree(s); }

    @Test
    void metricsMatchOnTheHeadlineValueWhateverTheColumnIsCalled() throws Exception {
        JsonNode view = j("{\"primitive\":\"metric\",\"value\":\"revenueYtdUsd\"}");
        JsonNode expected = j("[{\"revenueYtdUsd\":9085962,\"revenuePriorYtdUsd\":8044119}]");
        assertThat(EvalScorer.score(view, expected, j("[{\"ytdRevenue\":9085962.4}]")).pass()).isTrue();
        assertThat(EvalScorer.score(view, expected, j("[{\"ytdRevenue\":9300000}]")).pass()).isFalse();
        assertThat(EvalScorer.score(view, expected, j("[{\"a\":1},{\"a\":2}]")).reason()).contains("one row");
    }

    @Test
    void chartsMatchSeriesValuesInAnyOrder() throws Exception {
        JsonNode view = j("{\"primitive\":\"bar\",\"x\":\"lob\",\"y\":[\"revenueUsd\"]}");
        JsonNode expected = j("[{\"lob\":\"CL\",\"revenueUsd\":500},{\"lob\":\"CB\",\"revenueUsd\":300}]");
        assertThat(EvalScorer.score(view, expected, j("[{\"line\":\"CB\",\"rev\":300},{\"line\":\"CL\",\"rev\":500}]")).pass()).isTrue();
        assertThat(EvalScorer.score(view, expected, j("[{\"line\":\"CB\",\"rev\":300},{\"line\":\"CL\",\"rev\":560}]")).pass()).isFalse();
        assertThat(EvalScorer.score(view, expected, j("[{\"rev\":800}]")).reason()).contains("expected 2 rows");
    }

    @Test
    void tablesAndListsMatchRowCountsAndKeyColumns() throws Exception {
        JsonNode table = j("{\"primitive\":\"table\",\"columns\":[\"name\",\"headroomPct\"]}");
        JsonNode expected = j("[{\"name\":\"Min Liquidity\",\"headroomPct\":5.87},{\"name\":\"Max Leverage\",\"headroomPct\":59.7}]");
        assertThat(EvalScorer.score(table, expected, j("[{\"covenant\":\"Max Leverage\",\"headroom\":59.7},{\"covenant\":\"Min Liquidity\",\"headroom\":5.87}]")).pass()).isTrue();
        assertThat(EvalScorer.score(table, expected, j("[{\"covenant\":\"Min Liquidity\",\"headroom\":5.87}]")).pass()).isFalse();
        JsonNode list = j("{\"primitive\":\"list\",\"title\":\"name\"}");
        JsonNode people = j("[{\"name\":\"Carlos Hughes\",\"title\":\"CFO\"},{\"name\":\"Nina Patel\",\"title\":\"Treasurer\"}]");
        assertThat(EvalScorer.score(list, people, j("[{\"person\":\"Nina Patel\"},{\"person\":\"Carlos Hughes\"}]")).pass()).isTrue();
        assertThat(EvalScorer.score(list, people, j("[{\"person\":\"Nina Patel\"},{\"person\":\"Someone Else\"}]")).pass()).isFalse();
    }

    @Test
    void longFormatSeriesMatchAPivotedOrLongAnswer() throws Exception {
        JsonNode view = j("{\"primitive\":\"line\",\"x\":\"month\",\"y\":[\"revenueUsd\"],\"series\":\"lob\"}");
        JsonNode expected = j("[{\"month\":\"2026-08\",\"lob\":\"A\",\"revenueUsd\":10},{\"month\":\"2026-08\",\"lob\":\"B\",\"revenueUsd\":20},"
                + "{\"month\":\"2026-09\",\"lob\":\"A\",\"revenueUsd\":11},{\"month\":\"2026-09\",\"lob\":\"B\",\"revenueUsd\":21}]");
        assertThat(EvalScorer.score(view, expected, j("[{\"m\":\"Aug\",\"aUsd\":10,\"bUsd\":20},{\"m\":\"Sep\",\"aUsd\":11,\"bUsd\":21}]")).pass()).isTrue();
        assertThat(EvalScorer.score(view, expected, expected).pass()).isTrue();
        assertThat(EvalScorer.score(view, expected, j("[{\"m\":\"Aug\",\"aUsd\":10,\"bUsd\":25},{\"m\":\"Sep\",\"aUsd\":11,\"bUsd\":21}]")).reason())
                .contains("series B not found");
    }
}
