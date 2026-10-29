package com.aipb.semantic;

import com.aipb.source.GraphMcpSource;
import com.aipb.source.GraphSource;
import com.aipb.support.LiveSources;
import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Conformance of the semantic layer: every metric, run through the planner, the compiler and the driver (as pages read), must
 * match figures computed independently - in plain Java over raw nodes read with find (no aggregation, no shared
 * code with the compiler). Covers three clients, a client with no covenants (an edge case) and the whole book.
 * Needs the running stack: {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricConformanceLiveTest {

    private static final Set<String> CLOSED = Set.of("Closed Won", "Closed Lost");

    private final ObjectMapper mapper = new ObjectMapper();
    // the runtime path reads through the driver, as pages do; independent figures are computed from MCP reads
    private final GraphSource direct = LiveSources.graph(mapper);
    private final GraphMcpSource source = LiveSources.mcp(mapper, direct);
    private final QueryExecutor executor = new QueryExecutor(SemanticModel.fromClasspath(mapper), direct);
    private LocalDate asOf;
    private List<String> clients;

    @BeforeAll
    void setUp() {
        asOf = date(source.asOfDate());
        Set<String> withCovenants = new HashSet<>();
        docs("Covenant", Map.of()).forEach(c -> withCovenants.add(c.get("clientId").asText()));
        String noCovenants = docs("Client", Map.of()).stream().map(c -> c.get("clientId").asText())
                .filter(id -> !withCovenants.contains(id)).sorted().findFirst().orElseThrow();
        clients = List.of("CL-0001", "CL-0002", "CL-0017", noCovenants);
    }

    private ResultSet run(String json, String client) {
        try {
            ResultSet r = executor.execute(mapper.readValue(json, SemanticQuery.class), client);
            assertThat(r.truncated()).as("truncated").isFalse();
            return r;
        } catch (Exception e) {
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    @TestFactory
    Stream<DynamicTest> clientMetricsMatchIndependentFigures() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String c : clients) {
            tests.add(DynamicTest.dynamicTest("revenue YTD vs prior year / " + c, () -> revenueYtd(c)));
            tests.add(DynamicTest.dynamicTest("revenue by quarter / " + c, () -> revenueByQuarter(c)));
            tests.add(DynamicTest.dynamicTest("revenue by LOB T12M / " + c, () -> revenueByLob(c)));
            tests.add(DynamicTest.dynamicTest("exposure, deposits, utilization / " + c, () -> balances(c)));
            tests.add(DynamicTest.dynamicTest("pipeline / " + c, () -> pipeline(c)));
            tests.add(DynamicTest.dynamicTest("share of wallet / " + c, () -> shareOfWallet(c)));
            tests.add(DynamicTest.dynamicTest("signals / " + c, () -> signals(c)));
            tests.add(DynamicTest.dynamicTest("covenants / " + c, () -> covenants(c)));
            tests.add(DynamicTest.dynamicTest("contacts / " + c, () -> contacts(c)));
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ client figures
    private void revenueYtd(String c) {
        List<JsonNode> rev = docs("RevenueMonth", Map.of("clientId", c));
        LocalDate yearStart = asOf.withDayOfYear(1), monthStart = asOf.withDayOfMonth(1);
        double ytd = sum(rev, "totalUsd", d -> !month(d).isBefore(yearStart) && month(d).isBefore(monthStart));
        double prior = sum(rev, "totalUsd", d -> !month(d).isBefore(yearStart.minusYears(1)) && month(d).isBefore(monthStart.minusYears(1)));
        JsonNode row = run("{\"metrics\": [\"revenueUsd\", \"revenueYoY\"], \"window\": {\"type\": \"ytd\"}, \"compare\": \"priorYear\"}", c).rows().get(0);
        assertThat(row.get("revenueUsd").asDouble()).isCloseTo(ytd, within(0.5));
        assertThat(row.get("revenueUsdPrior").asDouble()).isCloseTo(prior, within(0.5));
        if (prior != 0) assertThat(row.get("revenueYoY").asDouble()).isCloseTo((ytd - prior) / prior, within(1e-9));
    }

    private void revenueByQuarter(String c) {
        List<JsonNode> rev = docs("RevenueMonth", Map.of("clientId", c));
        LocalDate qStart = asOf.withDayOfMonth(1).withMonth(((asOf.getMonthValue() - 1) / 3) * 3 + 1);
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"quarter\"], \"window\": {\"type\": \"lastClosed\", \"unit\": \"quarter\", \"n\": 8}}", c);
        assertThat(r.rows()).hasSize(8);
        for (int i = 0; i < 8; i++) {
            LocalDate from = qStart.minusMonths(3L * (8 - i)), to = from.plusMonths(3);
            JsonNode row = r.rows().get(i);
            assertThat(date(row.get("quarter").asText())).isEqualTo(from);
            assertThat(row.get("revenueUsd").asDouble()).as("quarter " + from)
                    .isCloseTo(sum(rev, "totalUsd", d -> !month(d).isBefore(from) && month(d).isBefore(to)), within(0.5));
        }
    }

    private void revenueByLob(String c) {
        List<JsonNode> rev = docs("RevenueMonth", Map.of("clientId", c));
        LocalDate to = asOf.withDayOfMonth(1), from = to.minusMonths(12);
        Map<String, Double> byLob = new HashMap<>();
        rev.stream().filter(d -> !month(d).isBefore(from) && month(d).isBefore(to))
                .forEach(d -> byLob.merge(d.get("lob").asText(), d.get("totalUsd").asDouble(), Double::sum));
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"lob\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 12}}", c);
        assertThat(r.rows()).hasSize(byLob.size());
        double prev = Double.MAX_VALUE;
        for (JsonNode row : r.rows()) {
            assertThat(row.get("revenueUsd").asDouble()).isCloseTo(byLob.get(row.get("lob").asText()), within(0.5));
            assertThat(row.get("revenueUsd").asDouble()).as("sorted largest first").isLessThanOrEqualTo(prev);
            prev = row.get("revenueUsd").asDouble();
        }
    }

    private void balances(String c) {
        List<JsonNode> h = docs("Holding", Map.of("clientId", c));
        Predicate<JsonNode> credit = d -> d.get("productType").asText().equals("credit");
        Predicate<JsonNode> deposit = d -> d.get("productType").asText().equals("deposit");
        double drawn = sum(h, "drawnUsd", credit), committed = sum(h, "committedUsd", credit);
        JsonNode row = run("{\"metrics\": [\"exposureUsd\", \"committedUsd\", \"utilization\", \"depositsUsd\", \"depositAccounts\", \"creditFacilities\", \"productsHeld\"]}", c).rows().get(0);
        assertThat(row.get("exposureUsd").asDouble()).isCloseTo(drawn, within(0.5));
        assertThat(row.get("committedUsd").asDouble()).isCloseTo(committed, within(0.5));
        if (committed > 0) assertThat(row.get("utilization").asDouble()).isCloseTo(drawn / committed, within(1e-9));
        else assertThat(row.get("utilization").isNull()).isTrue();
        assertThat(row.get("depositsUsd").asDouble()).isCloseTo(sum(h, "balanceUsd", deposit), within(0.5));
        assertThat(row.get("depositAccounts").asInt()).isEqualTo((int) h.stream().filter(deposit).count());
        assertThat(row.get("creditFacilities").asInt()).isEqualTo((int) h.stream().filter(credit).count());
        assertThat(row.get("productsHeld").asInt()).isEqualTo((int) h.stream().map(d -> d.get("productId").asText()).distinct().count());
    }

    private void pipeline(String c) {
        List<JsonNode> open = docs("Opportunity", Map.of("clientId", c)).stream().filter(d -> !CLOSED.contains(d.get("stage").asText())).toList();
        double weighted = open.stream().mapToDouble(d -> d.get("expectedRevenueUsd").asDouble() * d.get("probability").asDouble() / 100).sum();
        JsonNode row = run("{\"metrics\": [\"pipelineUsd\", \"openDeals\", \"weightedPipelineUsd\", \"stalledDeals\"]}", c).rows().get(0);
        assertThat(row.get("pipelineUsd").asDouble()).isCloseTo(sum(open, "expectedRevenueUsd", d -> true), within(0.5));
        assertThat(row.get("openDeals").asInt()).isEqualTo(open.size());
        assertThat(row.get("weightedPipelineUsd").asDouble()).isCloseTo(weighted, within(0.5));
        assertThat(row.get("stalledDeals").asInt()).isEqualTo((int) open.stream().filter(d -> d.get("daysInStage").asInt() > 45).count());

        ResultSet byStage = run("{\"metrics\": [\"pipelineUsd\"], \"dimensions\": [\"dealStage\"]}", c);
        Map<String, Double> expected = new HashMap<>();
        open.forEach(d -> expected.merge(d.get("stage").asText(), d.get("expectedRevenueUsd").asDouble(), Double::sum));
        assertThat(byStage.rows()).hasSize(expected.size());
        byStage.rows().forEach(r -> assertThat(r.get("pipelineUsd").asDouble()).isCloseTo(expected.get(r.get("dealStage").asText()), within(0.5)));
    }

    private void shareOfWallet(String c) {
        List<JsonNode> rev = docs("RevenueMonth", Map.of("clientId", c));
        LocalDate to = asOf.withDayOfMonth(1), from = to.minusMonths(12);
        double t12m = sum(rev, "totalUsd", d -> !month(d).isBefore(from) && month(d).isBefore(to));
        double wallet = docs("Client", Map.of("clientId", c)).get(0).get("walletSizeUsd").asDouble();
        JsonNode row = run("{\"metrics\": [\"shareOfWallet\", \"walletGapUsd\", \"revenueT12mUsd\", \"walletSizeUsd\"]}", c).rows().get(0);
        assertThat(row.get("revenueT12mUsd").asDouble()).isCloseTo(t12m, within(0.5));
        assertThat(row.get("walletSizeUsd").asDouble()).isCloseTo(wallet, within(0.5));
        assertThat(row.get("shareOfWallet").asDouble()).isCloseTo(t12m / wallet, within(1e-9));
        assertThat(row.get("walletGapUsd").asDouble()).isCloseTo(wallet - t12m, within(0.5));
    }

    private void signals(String c) {
        List<JsonNode> s = docs("Signal", Map.of("clientId", c));
        Predicate<JsonNode> active = d -> !d.get("stage").asText().equals("Resolved");
        JsonNode row = run("{\"metrics\": [\"activeSignals\", \"activeP1Signals\", \"riskAtStakeUsd\"]}", c).rows().get(0);
        assertThat(row.get("activeSignals").asInt()).isEqualTo((int) s.stream().filter(active).count());
        assertThat(row.get("activeP1Signals").asInt()).isEqualTo((int) s.stream().filter(active).filter(d -> d.get("severity").asText().equals("P1")).count());
        assertThat(row.get("riskAtStakeUsd").asDouble()).isCloseTo(sum(s, "impactUsd", active.and(d -> d.get("category").asText().equals("Risk"))), within(0.5));

        ResultSet list = run("{\"kind\": \"records\", \"model\": \"signals\", \"segments\": [\"active\"], \"fields\": [\"title\", \"severity\", \"detectedAt\"], \"limit\": 10}", c);
        List<JsonNode> expected = s.stream().filter(active).sorted(Comparator.<JsonNode, String>comparing(d -> d.get("severity").asText())
                .thenComparing(d -> d.get("detectedAt").asText(), Comparator.reverseOrder())).limit(10).toList();
        assertThat(list.rows()).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(list.rows().get(i).get("severity").asText()).isEqualTo(expected.get(i).get("severity").asText());
            assertThat(date(list.rows().get(i).get("detectedAt").asText())).isEqualTo(date(expected.get(i).get("detectedAt").asText()));
        }
        assertThat(list.hasMore()).isEqualTo(s.stream().filter(active).count() > 10);
    }

    private void covenants(String c) {
        List<JsonNode> cov = docs("Covenant", Map.of("clientId", c));
        Map<String, String> facility = new HashMap<>();
        docs("Holding", Map.of("clientId", c)).forEach(h -> facility.put(h.get("holdingId").asText(), h.get("productName").asText()));
        ResultSet list = run("{\"kind\": \"records\", \"model\": \"covenants\", \"fields\": [\"name\", \"facility\", \"actual\", \"threshold\", \"headroomPct\", \"status\"]}", c);
        assertThat(list.rows()).hasSize(cov.size());
        List<JsonNode> sorted = cov.stream().sorted(Comparator.comparingDouble(d -> d.get("headroomPct").asDouble())).toList();
        for (int i = 0; i < sorted.size(); i++) {
            JsonNode row = list.rows().get(i), d = sorted.get(i);
            assertThat(row.get("headroomPct").asDouble()).isCloseTo(d.get("headroomPct").asDouble(), within(1e-9));
            assertThat(row.get("unit").asText()).as("carried for actual/threshold").isNotBlank();
        }
        Set<String> facilities = new HashSet<>();
        cov.forEach(d -> facilities.add(facility.get(d.get("holdingId").asText())));
        list.rows().forEach(r -> assertThat(facilities).contains(r.get("facility").asText()));

        JsonNode kpi = run("{\"metrics\": [\"covenantCount\", \"breachedCovenants\", \"minHeadroomPct\"]}", c).rows().get(0);
        assertThat(kpi.get("covenantCount").asInt()).isEqualTo(cov.size());
        assertThat(kpi.get("breachedCovenants").asInt()).isEqualTo((int) cov.stream().filter(d -> d.get("status").asText().equals("Breach")).count());
        if (cov.isEmpty()) assertThat(kpi.get("minHeadroomPct").isNull()).as("no covenants: no tightest headroom").isTrue();
        else assertThat(kpi.get("minHeadroomPct").asDouble()).isCloseTo(sorted.get(0).get("headroomPct").asDouble(), within(1e-9));
    }

    private void contacts(String c) {
        List<JsonNode> ct = docs("Contact", Map.of("clientId", c));
        ResultSet list = run("{\"kind\": \"records\", \"model\": \"contacts\", \"fields\": [\"name\", \"title\", \"role\", \"lastContactDate\"]}", c);
        assertThat(list.rows()).hasSize(ct.size());
        String primary = ct.stream().filter(d -> d.get("isPrimary").asBoolean()).findFirst().orElseThrow().get("name").asText();
        assertThat(list.rows().get(0).get("name").asText()).isEqualTo(primary);
    }

    // ------------------------------------------------------------------ book figures
    @Test
    void bookRevenueByMonthAndLob() {
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"month\", \"lob\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 24}}", null);
        Map<String, Double> expected = new HashMap<>();
        for (LocalDate m : months(24))
            month(iso(m))
                    .forEach(d -> expected.merge(m + "|" + d.get("lob").asText(), d.get("totalUsd").asDouble(), Double::sum));
        assertThat(r.rows()).hasSize(24 * 4);
        for (JsonNode row : r.rows())
            assertThat(row.get("revenueUsd").asDouble()).as(row.toString())
                    .isCloseTo(expected.getOrDefault(date(row.get("month").asText()) + "|" + row.get("lob").asText(), 0.0), within(0.5));
        assertThat(date(r.rows().get(0).get("month").asText())).isEqualTo(asOf.withDayOfMonth(1).minusMonths(24));
    }

    @Test
    void bookTopClientsByRevenueInARegion() {
        Map<String, String> region = new HashMap<>(), name = new HashMap<>();
        docs("Client", Map.of()).forEach(c -> { region.put(c.get("clientId").asText(), c.get("region").asText()); name.put(c.get("clientId").asText(), c.get("name").asText()); });
        Map<String, Double> byClient = new HashMap<>();
        for (LocalDate m : months(12))
            month(iso(m)).stream().filter(d -> region.get(d.get("clientId").asText()).equals("EMEA"))
                    .forEach(d -> byClient.merge(d.get("clientId").asText(), d.get("totalUsd").asDouble(), Double::sum));
        List<Map.Entry<String, Double>> top = byClient.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(5).toList();
        ResultSet r = run("{\"metrics\": [\"revenueUsd\"], \"dimensions\": [\"client\"], \"window\": {\"type\": \"trailing\", \"unit\": \"month\", \"n\": 12},"
                + " \"filters\": [{\"on\": \"region\", \"op\": \"eq\", \"value\": \"EMEA\"}], \"limit\": 5}", null);
        assertThat(r.rows()).hasSize(top.size());
        for (int i = 0; i < top.size(); i++) {
            assertThat(r.rows().get(i).get("client").asText()).isEqualTo(top.get(i).getKey());
            assertThat(r.rows().get(i).get("clientName").asText()).isEqualTo(name.get(top.get(i).getKey()));
            assertThat(r.rows().get(i).get("revenueUsd").asDouble()).isCloseTo(top.get(i).getValue(), within(0.5));
        }
    }

    @Test
    void bookWinRateAndClientsAtRisk() {
        List<JsonNode> opp = docs("Opportunity", Map.of());
        long won = opp.stream().filter(d -> d.get("stage").asText().equals("Closed Won")).count();
        long lost = opp.stream().filter(d -> d.get("stage").asText().equals("Closed Lost")).count();
        List<JsonNode> cl = docs("Client", Map.of());
        JsonNode row = run("{\"metrics\": [\"winRate\", \"wonDeals\", \"clientCount\", \"atRiskClients\"]}", null).rows().get(0);
        assertThat(row.get("wonDeals").asLong()).isEqualTo(won);
        assertThat(row.get("winRate").asDouble()).isCloseTo((double) won / (won + lost), within(1e-9));
        assertThat(row.get("clientCount").asInt()).isEqualTo(cl.size());
        assertThat(row.get("atRiskClients").asInt()).isEqualTo((int) cl.stream().filter(c -> c.get("healthScore").asInt() < 50).count());
    }

    @Test
    void semanticModelAgreesWithTheLiveDictionary() {
        assertThat(SemanticModelValidator.againstCatalog(executor.model(), source.catalog())).isEmpty();
    }

    // ------------------------------------------------------------------ helpers
    private List<LocalDate> months(int n) {
        List<LocalDate> out = new ArrayList<>();
        for (int i = n; i >= 1; i--) out.add(asOf.withDayOfMonth(1).minusMonths(i));
        return out;
    }

    private List<JsonNode> docs(String label, Map<String, Object> filter) {
        SourceReader.Result r = source.find(label, filter, null, null, 1000);
        assertThat(r.truncated()).as("independent read of " + label + " truncated").isFalse();
        List<JsonNode> out = new ArrayList<>();
        r.documents().forEach(out::add);
        return out;
    }

    /** The revenue nodes of one month (a plain match on the datetime: no aggregation). */
    private List<JsonNode> month(String iso) {
        SourceReader.Result r = source.cypher("MATCH (n:RevenueMonth) WHERE n.month = datetime($m) RETURN n", Map.of("m", iso));
        assertThat(r.truncated()).as("independent read of RevenueMonth truncated").isFalse();
        List<JsonNode> out = new ArrayList<>();
        r.documents().forEach(d -> out.add(d.get("n")));
        return out;
    }

    private static double sum(List<JsonNode> docs, String field, Predicate<JsonNode> when) {
        return docs.stream().filter(when).filter(d -> d.hasNonNull(field)).mapToDouble(d -> d.get(field).asDouble()).sum();
    }

    private static LocalDate month(JsonNode d) { return date(d.get("month").asText()); }

    private static LocalDate date(String iso) { return LocalDate.ofInstant(Instant.parse(iso), ZoneOffset.UTC); }

    private static String iso(LocalDate d) { return d.atStartOfDay().toInstant(ZoneOffset.UTC).toString(); }
}
