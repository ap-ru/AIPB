package com.aipb.widget;

import com.aipb.semantic.ResultSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Insights are rendered by code: conditions, slots, formats, and statements left out when a figure is missing. */
class NarrativeRendererTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static ResultSet.Column col(String name, String type, String unit, String format, String label) {
        return new ResultSet.Column(name, label, type, unit, format, null, null, null, null, null, null, null);
    }

    private static final List<ResultSet.Column> COLS = List.of(
            col("pipelineUsd", "number", "USD", "currency", "Open pipeline"),
            col("commitUsd", "number", "USD", "currency", "Commit"),
            col("stalledDeals", "number", "count", "integer", "Stalled deals"),
            col("winRate", "number", "ratio", "percent", "Win rate"),
            col("tier", "string", "none", "text", "Tier"));

    private WidgetSpec.View view(String json) throws Exception {
        return mapper.readValue(json, WidgetSpec.View.class);
    }

    private JsonNode rows(String json) throws Exception { return mapper.readTree(json); }

    @Test
    void statementsShowWhenTheirConditionHoldsAndSlotsFormatThemselves() throws Exception {
        WidgetSpec.View v = view("""
                {"primitive":"narrative","figures":["pipelineUsd","stalledDeals"],"statements":[
                  {"text":"Open pipeline is {pipelineUsd}, {commitUsd} of it in Commit."},
                  {"when":{"column":"stalledDeals","op":">=","value":3},"text":"{stalledDeals} deals have stalled.","tone":"bad"},
                  {"when":{"column":"stalledDeals","op":">=","value":1},"text":"{stalledDeals} {stalledDeals=1?deal has:deals have} stalled.","tone":"warn"},
                  {"when":{"column":"tier","op":"=","value":"Strategic"},"text":"A strategic client: win rate {winRate}, or {winRate:number}.","tone":"info"}]}""");
        NarrativeRenderer.Narrative n = NarrativeRenderer.render(v, COLS,
                rows("[{\"pipelineUsd\":12500000,\"commitUsd\":850000,\"stalledDeals\":1,\"winRate\":0.083,\"tier\":\"Strategic\"}]"));
        assertThat(n.lines()).extracting(NarrativeRenderer.Line::text).containsExactly(
                "Open pipeline is $12.5M, $850K of it in Commit.", "1 deal has stalled.", "A strategic client: win rate 8.3%, or 0.08.");
        assertThat(n.lines()).extracting(NarrativeRenderer.Line::tone).containsExactly("neutral", "warn", "info");
        assertThat(n.figures()).extracting(NarrativeRenderer.Figure::text).containsExactly("$12.5M", "1");
    }

    @Test
    void aStatementWhoseFigureIsMissingIsLeftOutAndNoRowsSayNothing() throws Exception {
        WidgetSpec.View v = view("""
                {"primitive":"narrative","statements":[{"text":"Pipeline {pipelineUsd}."},{"text":"Commit {commitUsd}."},
                  {"when":{"column":"commitUsd","op":">","value":0},"text":"Something in Commit."}]}""");
        NarrativeRenderer.Narrative n = NarrativeRenderer.render(v, COLS, rows("[{\"pipelineUsd\":-2500,\"commitUsd\":null}]"));
        assertThat(n.lines()).extracting(NarrativeRenderer.Line::text).containsExactly("Pipeline -$3K.");
        assertThat(n.skipped()).isEqualTo(1); // the false condition is not a skip
        assertThat(NarrativeRenderer.render(v, COLS, rows("[]")).lines()).isEmpty();
    }

    @Test
    void problemsNameEveryBadSlotConditionAndFigure() throws Exception {
        WidgetSpec.View v = view("""
                {"primitive":"narrative","figures":["nope"],"statements":[
                  {"text":"{pipelineUsd} {missing} {pipelineUsd:fancy} {tier>1?a:b} {not a slot}"},
                  {"when":{"column":"tier","op":">","value":"x"},"text":"ok"}]}""");
        Map<String, ResultSet.Column> cols = new java.util.LinkedHashMap<>();
        COLS.forEach(c -> cols.put(c.name(), c));
        List<String> problems = NarrativeRenderer.problems(v, cols, Set.of("currency", "percent", "number")).stream()
                .map(NarrativeRenderer.Problem::message).toList();
        assertThat(problems).anyMatch(m -> m.contains("{missing}")).anyMatch(m -> m.contains("format 'fancy'"))
                .anyMatch(m -> m.contains("{tier>1?a:b} compares 'tier', which is not a number")).anyMatch(m -> m.contains("{not a slot} is not a slot"))
                .anyMatch(m -> m.contains("compare it with = or != only")).anyMatch(m -> m.contains("figure 'nope'"));
    }
}
