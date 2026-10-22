package com.aipb.context;

import com.aipb.model.Layout;
import com.aipb.seed.Defaults;
import com.aipb.semantic.*;
import com.aipb.service.LayoutUpgrades;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Page context: window keys, resolving page + URL + placement, how queries take a period and filters, and the layout upgrade. */
class PageContextTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);
    private final ContextResolver resolver = new ContextResolver(sm, null);
    private final QueryPlanner planner = new QueryPlanner(sm);
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 16);

    private SemanticQuery q(String json) throws Exception { return mapper.readValue(json, SemanticQuery.class); }

    private Layout.Page commandCenter() {
        Layout.Page p = Defaults.page("command-center", "Command Center", "grid", "", true);
        p.setContext(Defaults.commandCenterContext());
        return p;
    }

    // ------------------------------------------------------------------ window keys
    @Test
    void windowKeysRoundTrip() {
        for (String k : List.of("all", "ytd", "trailing:month:12", "lastClosed:quarter:8", "current:quarter:1", "next:day:90@maturity",
                "range:2026-01-01:2026-07-01")) {
            assertThat(Window.parseKey(k).key()).isEqualTo(k);
        }
        assertThat(Window.parseKey("current:quarter").n()).isEqualTo(1);
        assertThatThrownBy(() -> Window.parseKey("trailing:month")).hasMessageContaining("needs n");
        assertThatThrownBy(() -> Window.parseKey("trailing:month:x")).hasMessageContaining("must be a number");
        assertThatThrownBy(() -> Window.parseKey("soon")).hasMessageContaining("must be one of");
    }

    // ------------------------------------------------------------------ resolving
    @Test
    void thePageDefaultsTheUrlChoosesAndThePlacementWins() {
        Layout.Page cc = commandCenter();
        ContextResolver.Resolved d = resolver.resolve(cc, Map.of(), Map.of());
        assertThat(d.period().key()).isEqualTo("trailing:month:12");
        assertThat(d.periodLabel()).isEqualTo("Last 12 months");
        assertThat(d.filters()).isEmpty();
        assertThat(d.entityId()).as("Command Center has no client picker").isNull();

        ContextResolver.Resolved u = resolver.resolve(cc, Map.of(), Map.of("period", "ytd", "region", "EMEA", "lob", "Capital Markets,Global Markets",
                "clientId", "CL-0001", "industry", "Energy"));
        assertThat(u.period().key()).isEqualTo("ytd");
        assertThat(u.entityId()).as("a client in the URL does not apply to a page without a picker").isNull();
        assertThat(u.filters()).extracting(SemanticQuery.Filter::on).containsExactly("region", "lob").as("only the page's filter slots");
        assertThat(u.filters().get(1).op()).isEqualTo("in");

        Map<String, Object> config = Map.of("bindings", Map.of("period", "t24m", "filters", Map.of("region", "", "tier", "Strategic")));
        ContextResolver.Resolved b = resolver.resolve(cc, config, Map.of("period", "ytd", "region", "EMEA"));
        assertThat(b.period().key()).isEqualTo("trailing:month:24");
        assertThat(b.filters()).extracting(SemanticQuery.Filter::on).containsExactly("tier");

        assertThatThrownBy(() -> resolver.resolve(cc, Map.of(), Map.of("period", "decade"))).hasMessageContaining("this page offers: t12m, ytd, l4q, t24m");
    }

    @Test
    void theClientComesFromThePicker() {
        Layout.Page client = Defaults.defaultLayout().page("client");
        assertThat(resolver.resolve(client, Map.of(), Map.of("clientId", "CL-0002")).entityId()).isEqualTo("CL-0002");
        assertThat(resolver.resolve(client, Map.of("params", Map.of("clientId", "CL-0009")), Map.of("clientId", "CL-0002")).entityId())
                .as("a placement fixed to one client").isEqualTo("CL-0009");
        assertThat(resolver.fromRequest(Map.of("clientId", "CL-1", "period", "ytd", "filter.region", "EMEA")).filters()).hasSize(1);
    }

    // ------------------------------------------------------------------ queries in context
    @Test
    void onlyAQueryThatFollowsThePageTakesItsPeriod() throws Exception {
        QueryContext ctx = new QueryContext(AS_OF, null, Window.parseKey("ytd"), List.of());
        QueryPlanner.Plan follows = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 24}, \"period\": \"page\"}"), ctx);
        assertThat(follows.queries().get(0).range().start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(follows.applied().period()).isEqualTo("calendar year to date (Jan 1, 2026 - Sep 30, 2026)");
        QueryPlanner.Plan fixed = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 24}}"), ctx);
        assertThat(fixed.queries().get(0).range().start()).isEqualTo(LocalDate.of(2024, 10, 1));
        QueryPlanner.Plan noPage = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 24}, \"period\": \"page\"}"),
                QueryContext.book(AS_OF));
        assertThat(noPage.queries().get(0).range().start()).as("no page period: its own window").isEqualTo(LocalDate.of(2024, 10, 1));
    }

    @Test
    void pageFiltersApplyWhereTheyCanAndSaySoWhereTheyCannot() throws Exception {
        List<SemanticQuery.Filter> filters = resolver.filters(new LinkedHashMap<>(Map.of("region", "EMEA")));
        filters = new ArrayList<>(filters);
        filters.addAll(resolver.filters(Map.of("lob", "Capital Markets")));
        QueryContext ctx = new QueryContext(AS_OF, null, null, filters);

        QueryPlanner.Plan revenue = planner.plan(q("{\"metrics\": [\"revenueUsd\"]}"), ctx);
        String p = PipelineCompiler.compile(revenue.queries().get(0)).toString();
        assertThat(p).contains("\"_j_client.region\":{\"$eq\":\"EMEA\"}").contains("\"lob\":{\"$eq\":\"Capital Markets\"}");
        assertThat(revenue.applied().filters()).containsExactly("Region: EMEA", "Line of business: Capital Markets");
        assertThat(revenue.notes()).isEmpty();

        QueryPlanner.Plan wallet = planner.plan(q("{\"metrics\": [\"walletSizeUsd\", \"clientCount\"]}"), ctx);
        assertThat(wallet.applied().filters()).containsExactly("Region: EMEA");
        assertThat(wallet.notes()).singleElement().asString().contains("Not filtered by line of business").contains("Clients");

        QueryPlanner.Plan ignoring = planner.plan(q("{\"metrics\": [\"revenueUsd\"], \"pageFilters\": false}"), ctx);
        assertThat(PipelineCompiler.compile(ignoring.queries().get(0)).toString()).doesNotContain("EMEA");

        QueryPlanner.Plan records = planner.plan(q("{\"kind\": \"records\", \"model\": \"contacts\"}"), ctx);
        assertThat(records.applied().filters()).containsExactly("Region: EMEA");
        assertThat(records.notes()).anyMatch(n -> n.contains("Not filtered by line of business"));
    }

    @Test
    void aBadPageFilterValueIsRefused() throws Exception {
        QueryContext ctx = new QueryContext(AS_OF, null, null, resolver.filters(Map.of("region", "Mars")));
        assertThatThrownBy(() -> planner.plan(q("{\"metrics\": [\"revenueUsd\"]}"), ctx)).hasMessageContaining("'Mars' is not a value of region");
    }

    @Test
    void currentFiguresSayTheyAreCurrent() throws Exception {
        QueryPlanner.Plan p = planner.plan(q("{\"metrics\": [\"exposureUsd\"]}"), new QueryContext(AS_OF, "CL-1", Window.parseKey("ytd"), List.of()));
        assertThat(p.applied()).isEqualTo(new ResultSet.Applied("entity", "CL-1", "current, as of 2026-10-16", List.of()));
    }

    // ------------------------------------------------------------------ layout upgrade
    @Test
    void theUpgradeAddsContextAndMissingWidgetsOnce() {
        Layout l = Defaults.defaultLayout();
        Layout.Page cc = l.page("command-center");
        cc.setContext(null);
        cc.getWidgets().clear();
        cc.getWidgets().add(Defaults.spec("book-revenue-by-lob-monthly", "Monthly revenue", 12));
        cc.getWidgets().add(Defaults.spec("my-own-widget", "Mine", 6));
        assertThat(LayoutUpgradesAccess.add(l)).isTrue();
        assertThat(cc.getContext().getPeriod().getDefaultOption()).isEqualTo("t12m");
        assertThat(cc.getWidgets()).extracting(w -> String.valueOf(w.getConfig().get("definitionId")))
                .containsExactly("client-exposure", "client-deposits", "client-open-pipeline", "book-revenue-by-lob-monthly", "client-revenue-by-lob", "my-own-widget");
        assertThat(LayoutUpgradesAccess.add(l)).as("idempotent").isFalse();
    }

    /** Reaches the package-private upgrade step. */
    static final class LayoutUpgradesAccess {
        static boolean add(Layout l) {
            try {
                var m = LayoutUpgrades.class.getDeclaredMethod("addPageContext", Layout.class);
                m.setAccessible(true);
                return (boolean) m.invoke(null, l);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
