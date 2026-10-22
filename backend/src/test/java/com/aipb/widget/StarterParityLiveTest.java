package com.aipb.widget;

import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.SemanticModel;
import com.aipb.source.McpClient;
import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Parity: every starter widget, now a semantic query, returns the same figures as the hand-written pipeline it replaced
 * (kept in test resources legacy-starters/), for every client in the source and for the book. Column names may differ
 * (the semantic layer names figures by metric id); the numbers may not. Two deliberate differences are allowed: a
 * quarter with no revenue now appears as 0 instead of being missing, and utilization with no committed limit is
 * unknown (null) instead of 0. Needs the running stack: {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
class StarterParityLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SourceData source = new SourceData(
            new McpClient(mapper, System.getProperty("aipb.mcp-url", "http://localhost:3001/mcp"), 30), "bank");
    private final WidgetDataService data = new WidgetDataService(source, null, mapper,
            new QueryExecutor(SemanticModel.fromClasspath(mapper), source));

    private static final List<String> IDS = List.of("client-revenue-ytd", "client-exposure", "client-deposits", "client-open-pipeline",
            "client-revenue-trend", "client-revenue-by-lob", "client-holdings", "client-covenants", "client-open-opportunities",
            "client-active-signals", "client-contacts");

    @TestFactory
    Stream<DynamicTest> startersMatchTheHandWrittenPipelinesForEveryClient() throws Exception {
        List<String> clients = new ArrayList<>();
        source.find("clients", null, Map.of("clientId", 1), Map.of("clientId", 1), 1000).documents().forEach(c -> clients.add(c.get("clientId").asText()));
        assertThat(clients).hasSizeGreaterThan(10);
        List<DynamicTest> tests = new ArrayList<>();
        for (String id : IDS) {
            WidgetSpec legacy = SpecMigrator.read(mapper, read("legacy-starters/" + id + ".json"));
            WidgetSpec now = SpecMigrator.read(mapper, read("widgets/starter/" + id + ".json"));
            assertThat(now.isQuery()).as(id + " is a semantic query").isTrue();
            tests.add(DynamicTest.dynamicTest(id + " / all " + clients.size() + " clients", () -> {
                for (String c : clients) {
                    JsonNode before = data.execute(legacy, Map.of("clientId", c)).rows();
                    WidgetDataService.DataResult r = data.execute(now, Map.of("clientId", c));
                    assertThat(r.truncated()).isFalse();
                    compare(id, c, before, r.rows());
                }
            }));
        }
        tests.add(DynamicTest.dynamicTest("book-revenue-by-lob-monthly / book", () -> {
            JsonNode before = data.execute(SpecMigrator.read(mapper, read("legacy-starters/book-revenue-by-lob-monthly.json")), Map.of()).rows();
            JsonNode after = data.execute(SpecMigrator.read(mapper, read("widgets/starter/book-revenue-by-lob-monthly.json")), Map.of()).rows();
            Map<String, String> field = Map.of("Corporate Banking", "corporateBankingUsd", "Commercial Lending", "commercialLendingUsd",
                    "Capital Markets", "capitalMarketsUsd", "Global Markets", "globalMarketsUsd");
            Map<String, Double> want = new HashMap<>();
            before.forEach(row -> field.forEach((lob, f) -> want.put(day(row.get("month")) + "|" + lob, row.get(f).asDouble())));
            assertThat(after).hasSize(want.size());
            after.forEach(row -> assertThat(row.get("revenueUsd").asDouble()).as(row.toString())
                    .isCloseTo(want.get(day(row.get("month")) + "|" + row.get("lob").asText()), within(0.01)));
        }));
        return tests.stream();
    }

    private void compare(String id, String c, JsonNode before, JsonNode after) {
        String at = id + " " + c;
        switch (id) {
            case "client-revenue-ytd" -> {
                same(at, before.get(0).get("revenueYtdUsd"), after.get(0).get("revenueUsd"));
                same(at, before.get(0).get("revenuePriorYtdUsd"), after.get(0).get("revenueUsdPrior"));
            }
            case "client-exposure" -> {
                same(at, before.get(0).get("exposureUsd"), after.get(0).get("exposureUsd"));
                same(at, before.get(0).get("committedUsd"), after.get(0).get("committedUsd"));
                if (before.get(0).get("committedUsd").asDouble() == 0) assertThat(after.get(0).get("utilization").isNull()).as(at).isTrue();
                else same(at, before.get(0).get("utilization"), after.get(0).get("utilization"));
            }
            case "client-deposits" -> {
                same(at, before.get(0).get("depositsUsd"), after.get(0).get("depositsUsd"));
                same(at, before.get(0).get("accounts"), after.get(0).get("depositAccounts"));
            }
            case "client-open-pipeline" -> {
                if (before.isEmpty()) { assertThat(after.get(0).get("openDeals").asInt()).as(at).isZero(); return; }
                same(at, before.get(0).get("pipelineUsd"), after.get(0).get("pipelineUsd"));
                same(at, before.get(0).get("openDeals"), after.get(0).get("openDeals"));
            }
            case "client-revenue-trend" -> {
                Map<String, Double> want = new HashMap<>();
                before.forEach(r -> want.put(r.get("quarter").asText(), r.get("revenueUsd").asDouble()));
                assertThat(after).as(at).hasSize(8);
                for (JsonNode r : after) {
                    LocalDate q = day(r.get("quarter"));
                    String label = q.getYear() + " Q" + ((q.getMonthValue() - 1) / 3 + 1);
                    assertThat(r.get("revenueUsd").asDouble()).as(at + " " + label).isCloseTo(want.getOrDefault(label, 0.0), within(0.01));
                }
            }
            case "client-revenue-by-lob" -> {
                assertThat(after).as(at).hasSize(before.size());
                for (int i = 0; i < before.size(); i++) {
                    assertThat(after.get(i).get("lob").asText()).as(at).isEqualTo(before.get(i).get("lob").asText());
                    same(at, before.get(i).get("revenueUsd"), after.get(i).get("revenueUsd"));
                }
            }
            case "client-holdings" -> {
                assertThat(after).as(at).hasSize(before.size());
                assertThat(tuples(after, "productName", "amountUsd", "committedUsd", "maturityDate"))
                        .as(at).isEqualTo(tuples(before, "productName", "amountUsd", "committedUsd", "maturityDate"));
            }
            case "client-covenants" -> {
                assertThat(after).as(at).hasSize(before.size());
                assertThat(tuples(after, "headroomPct", "facility", "name", "status")).as(at).isEqualTo(tuples(before, "headroomPct", "facility", "name", "status"));
                for (int i = 0; i < before.size(); i++) same(at, before.get(i).get("headroomPct"), after.get(i).get("headroomPct"));
            }
            case "client-open-opportunities" -> {
                assertThat(after).as(at).hasSize(before.size());
                for (int i = 0; i < before.size(); i++) same(at, before.get(i).get("expectedRevenueUsd"), after.get(i).get("expectedRevenueUsd"));
            }
            case "client-active-signals" -> {
                assertThat(after).as(at).hasSize(before.size());
                for (int i = 0; i < before.size(); i++) {
                    assertThat(after.get(i).get("severity").asText()).as(at).isEqualTo(before.get(i).get("severity").asText());
                    assertThat(day(after.get(i).get("detectedAt"))).as(at).isEqualTo(day(before.get(i).get("detectedAt")));
                }
                assertThat(tuples(after, "title", "subtitle")).as(at).isEqualTo(tuples(before, "title", "subtitle"));
            }
            case "client-contacts" -> {
                assertThat(after).as(at).hasSize(before.size());
                assertThat(after.get(0).get("name").asText()).as(at + " primary first").isEqualTo(before.get(0).get("name").asText());
                assertThat(tuples(after, "name", "title", "role")).as(at).isEqualTo(tuples(before, "name", "title", "role"));
            }
            default -> throw new AssertionError("no parity check for " + id);
        }
    }

    private static void same(String at, JsonNode want, JsonNode got) {
        assertThat(got).as(at + " present").isNotNull();
        assertThat(got.asDouble()).as(at).isCloseTo(want.asDouble(), within(Math.max(0.01, Math.abs(want.asDouble()) * 1e-12)));
    }

    /** Rows as a sorted list of value tuples (dates compared by day), so equal sets in any order compare equal. */
    private static List<String> tuples(JsonNode rows, String... fields) {
        List<String> out = new ArrayList<>();
        for (JsonNode r : rows) {
            StringJoiner j = new StringJoiner(" | ");
            for (String f : fields) {
                JsonNode v = r.get(f);
                j.add(v == null || v.isNull() ? "null" : v.isNumber() ? String.format("%.4f", v.asDouble())
                        : v.asText().matches("\\d{4}-\\d\\d-\\d\\dT.*") ? day(v).toString() : v.asText());
            }
            out.add(j.toString());
        }
        Collections.sort(out);
        return out;
    }

    private static LocalDate day(JsonNode v) { return LocalDate.ofInstant(Instant.parse(v.asText()), ZoneOffset.UTC); }

    private JsonNode read(String path) throws Exception {
        return mapper.readTree(new ClassPathResource(path).getInputStream());
    }
}
