package com.aipb.semantic;

import org.junit.jupiter.api.Test;

import static com.aipb.semantic.PipelineCompilerTest.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The planner refuses what the semantic model cannot answer, saying what to change. */
class QueryPlannerTest {

    private final QueryPlanner planner = new QueryPlanner(SM);

    private SemanticQueryException refused(String json, QueryContext ctx) {
        try {
            planner.plan(q(json), ctx);
        } catch (SemanticQueryException e) {
            return e;
        }
        throw new AssertionError("expected the query to be refused: " + json);
    }

    @Test
    void unknownMetricListsTheMenu() {
        SemanticQueryException e = refused("{\"metrics\": [\"revenu\"]}", CLIENT);
        assertThat(e.issues()).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("metrics[0]");
            assertThat(i.message()).contains("unknown metric 'revenu'").contains("revenueUsd");
        });
    }

    @Test
    void currentBalancesHaveNoHistory() {
        assertThat(refused("{\"metrics\": [\"depositsUsd\"], \"dimensions\": [\"month\"]}", CLIENT).getMessage())
                .contains("no history").contains("month");
    }

    @Test
    void dimensionsMustBeAvailableInEveryModelTouched() {
        assertThat(refused("{\"metrics\": [\"revenueUsd\", \"pipelineUsd\"], \"dimensions\": [\"dealStage\"]}", CLIENT).getMessage())
                .contains("'Revenue' figures cannot be broken down by dealStage");
    }

    @Test
    void filterValuesAreCheckedAgainstTheDimension() {
        assertThat(refused("{\"metrics\": [\"revenueUsd\"], \"filters\": [{\"on\": \"lob\", \"op\": \"eq\", \"value\": \"Retail\"}]}", CLIENT).getMessage())
                .contains("'Retail' is not a value of lob");
        assertThat(refused("{\"metrics\": [\"revenueUsd\"], \"filters\": [{\"on\": \"lob\", \"op\": \"in\", \"value\": \"Capital Markets\"}]}", CLIENT).getMessage())
                .contains("needs a list");
    }

    @Test
    void comparisonsNeedAPriorPeriod() {
        assertThat(refused("{\"metrics\": [\"revenueUsd\"], \"compare\": \"priorPeriod\"}", CLIENT).getMessage()).contains("needs a window");
    }

    @Test
    void aChangeMetricGetsNoPriorColumnOfItsOwn() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\", \"revenueYoY\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}"), CLIENT);
        assertThat(plan.outputs()).extracting(o -> o.column().name()).containsExactly("revenueUsd", "revenueYoY", "revenueUsdPrior");
        assertThat(plan.notes()).anyMatch(n -> n.contains("revenueYoY is already a change"));
        assertThat(plan.queries()).as("growth reuses the same two revenue queries").hasSize(2);
    }

    @Test
    void entityScopeNeedsASelectedClient() {
        assertThat(refused("{\"metrics\": [\"revenueUsd\"], \"scope\": \"entity\"}", BOOK).getMessage()).contains("needs a selected client");
    }

    @Test
    void recordsQueriesCheckFieldsAndSorts() {
        SemanticQueryException e = refused("{\"kind\": \"records\", \"model\": \"covenants\", \"fields\": [\"name\", \"ebitda\"], \"sort\": [{\"by\": \"nope\"}]}", CLIENT);
        assertThat(e.issues()).hasSize(2);
        assertThat(refused("{\"kind\": \"records\", \"model\": \"revenue\"}", CLIENT).getMessage()).contains("records need a model that lists records");
    }

    @Test
    void currentFiguresIgnoreAWindowWithANote() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"depositsUsd\"], \"window\": {\"type\": \"ytd\"}}"), CLIENT);
        assertThat(plan.notes()).singleElement().asString().contains("current figure");
        assertThat(plan.queries().get(0).range()).isNull();
    }

    @Test
    void perRowUnitsBringTheirUnitColumn() {
        QueryPlanner.Plan plan = planner.plan(q("{\"kind\": \"records\", \"model\": \"covenants\", \"fields\": [\"name\", \"actual\"]}"), CLIENT);
        assertThat(plan.fieldColumns()).extracting(ResultSet.Column::name).contains("unit");
        ResultSet.Column actual = plan.fieldColumns().stream().filter(c -> c.name().equals("actual")).findFirst().orElseThrow();
        assertThat(actual.unitFrom().column()).isEqualTo("unit");
        assertThat(actual.unitFrom().map()).containsEntry("x", "multiple");
    }

    @Test
    void lineageCarriesTheDefinitionSourcesAndResolvedWindow() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"revenueUsd\", \"utilization\"], \"window\": {\"type\": \"ytd\"}}"), CLIENT);
        ResultSet.Lineage rev = plan.outputs().get(0).column().lineage();
        assertThat(rev.sources()).containsExactly("revenue_monthly.totalUsd");
        assertThat(rev.window()).isEqualTo("calendar year to date (Jan 1, 2026 - Sep 30, 2026)");
        ResultSet.Lineage util = plan.outputs().get(1).column().lineage();
        assertThat(util.kind()).isEqualTo("metric");
        assertThat(util.definition()).contains("exposureUsd / committedUsd");
        assertThat(util.sources()).containsExactly("holdings.drawnUsd", "holdings.committedUsd");
        assertThat(util.window()).isEqualTo("current, as of 2026-10-16");
    }

    @Test
    void lineageWindowsDescribeWhatEachFigureActuallyCovers() {
        QueryPlanner.Plan plan = planner.plan(q("{\"metrics\": [\"shareOfWallet\", \"revenueYoY\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}"), CLIENT);
        assertThat(plan.outputs().get(0).column().lineage().window())
                .isEqualTo("last 12 months (Oct 1, 2025 - Sep 30, 2026); current, as of 2026-10-16");
        assertThat(plan.outputs().get(1).column().lineage().window())
                .isEqualTo("calendar year to date (Jan 1, 2026 - Sep 30, 2026) vs a year earlier");
        assertThat(plan.outputs().get(2).column().lineage().window())
                .isEqualTo("last 12 months (Oct 1, 2024 - Sep 30, 2025); current, as of 2026-10-16");
    }
}
