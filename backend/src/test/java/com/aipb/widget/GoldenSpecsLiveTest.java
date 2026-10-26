package com.aipb.widget;

import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.SemanticModel;
import com.aipb.source.DirectSource;
import com.aipb.source.SourceData;
import com.aipb.support.LiveSources;
import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The golden set: every starter spec, run through the driver as pages read it, must match figures computed independently -
 * in plain Java over raw documents read with find (no aggregation). Needs the running stack:
 * <pre>docker compose up -d   then   mvn test -Dgroups=live -Dsurefire.excludedGroups=</pre>
 * The cases' natural-language requests (golden/cases.json) are what the assistant will later be evaluated on.
 */
@Tag("live")
class GoldenSpecsLiveTest {

    private static final List<String> CLIENTS = List.of("CL-0001", "CL-0002", "CL-0017");
    private static final Set<String> CLOSED = Set.of("Closed Won", "Closed Lost");

    private final ObjectMapper mapper = new ObjectMapper();
    // the runtime path reads through the driver, as pages do; independent figures are computed from MCP reads
    private final DirectSource direct = LiveSources.direct(mapper);
    private final SourceData source = LiveSources.mcp(mapper);
    private final WidgetDataService data = new WidgetDataService(direct, null, mapper, new QueryExecutor(SemanticModel.fromClasspath(mapper), direct));
    private LocalDate asOf;

    @TestFactory
    Stream<DynamicTest> starterSpecsMatchIndependentFigures() throws Exception {
        asOf = date(source.asOfDate());
        JsonNode cases = mapper.readTree(new ClassPathResource("golden/cases.json").getInputStream());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            String id = c.get("definitionId").asText();
            WidgetSpec spec = SpecMigrator.read(mapper, mapper.readTree(new ClassPathResource("widgets/starter/" + id + ".json").getInputStream()));
            List<String> subjects = c.get("scope").asText().equals("book") ? List.of("book") : CLIENTS;
            for (String subject : subjects) {
                tests.add(DynamicTest.dynamicTest(id + " / " + subject, () -> {
                    Map<String, String> params = subject.equals("book") ? Map.of() : Map.of("clientId", subject);
                    WidgetDataService.DataResult r = data.execute(spec, params);
                    assertThat(r.truncated()).as("truncated").isFalse();
                    check(id, subject, r.rows());
                }));
            }
        }
        return tests.stream();
    }

    // ------------------------------------------------------------------ independent figures, per spec
    private void check(String id, String client, JsonNode rows) {
        switch (id) {
            case "client-revenue-ytd" -> {
                List<JsonNode> rev = docs("revenue_monthly", Map.of("clientId", client));
                LocalDate yearStart = asOf.withDayOfYear(1);
                LocalDate priorStart = yearStart.minusYears(1);
                LocalDate priorEnd = asOf.withDayOfMonth(1).minusYears(1);
                assertThat(num(rows.get(0), "revenueUsd")).isCloseTo(sum(rev, "totalUsd", d -> !month(d).isBefore(yearStart)), within(0.5));
                assertThat(num(rows.get(0), "revenueUsdPrior")).isCloseTo(sum(rev, "totalUsd",
                        d -> !month(d).isBefore(priorStart) && month(d).isBefore(priorEnd)), within(0.5));
            }
            case "client-exposure" -> {
                List<JsonNode> credit = docs("holdings", Map.of("clientId", client, "productType", "credit"));
                double drawn = sum(credit, "drawnUsd", d -> true);
                double committed = sum(credit, "committedUsd", d -> true);
                assertThat(num(rows.get(0), "exposureUsd")).isCloseTo(drawn, within(0.5));
                assertThat(num(rows.get(0), "utilization")).isCloseTo(drawn / committed, within(1e-9));
            }
            case "client-deposits" -> {
                List<JsonNode> dep = docs("holdings", Map.of("clientId", client, "productType", "deposit"));
                assertThat(num(rows.get(0), "depositsUsd")).isCloseTo(sum(dep, "balanceUsd", d -> true), within(0.5));
                assertThat(num(rows.get(0), "depositAccounts")).isEqualTo(dep.size());
            }
            case "client-open-pipeline" -> {
                List<JsonNode> open = docs("opportunities", Map.of("clientId", client)).stream().filter(this::isOpen).toList();
                assertThat(num(rows.get(0), "pipelineUsd")).isCloseTo(sum(open, "expectedRevenueUsd", d -> true), within(0.5));
                assertThat(num(rows.get(0), "openDeals")).isEqualTo(open.size());
            }
            case "client-revenue-trend" -> {
                // every one of the last 8 closed quarters, oldest first; a quarter without revenue is 0
                LocalDate qStart = asOf.withDayOfMonth(1).withMonth((asOf.getMonthValue() - 1) / 3 * 3 + 1);
                List<JsonNode> rev = docs("revenue_monthly", Map.of("clientId", client));
                assertThat(rows.size()).isEqualTo(8);
                for (int i = 0; i < 8; i++) {
                    LocalDate from = qStart.minusMonths(3L * (8 - i)), to = from.plusMonths(3);
                    assertThat(date(rows.get(i).get("quarter").asText())).isEqualTo(from);
                    assertThat(num(rows.get(i), "revenueUsd")).isCloseTo(rev.stream().filter(d -> !month(d).isBefore(from) && month(d).isBefore(to))
                            .mapToDouble(d -> d.get("totalUsd").asDouble()).sum(), within(0.5));
                }
            }
            case "client-revenue-by-lob" -> {
                LocalDate from = asOf.withDayOfMonth(1).minusMonths(12);
                Map<String, Double> byLob = new HashMap<>();
                docs("revenue_monthly", Map.of("clientId", client)).stream().filter(d -> !month(d).isBefore(from))
                        .forEach(d -> byLob.merge(d.get("lob").asText(), d.get("totalUsd").asDouble(), Double::sum));
                assertThat(rows.size()).isEqualTo(byLob.size());
                double prev = Double.MAX_VALUE;
                for (JsonNode r : rows) {
                    assertThat(num(r, "revenueUsd")).isCloseTo(byLob.get(r.get("lob").asText()), within(0.5)).isLessThanOrEqualTo(prev);
                    prev = num(r, "revenueUsd");
                }
            }
            case "client-holdings" -> {
                List<JsonNode> h = docs("holdings", Map.of("clientId", client));
                assertThat(rows.size()).isEqualTo(h.size());
                Map<String, Double> amounts = new HashMap<>();
                h.forEach(d -> amounts.put(d.get("productName").asText(),
                        d.has("drawnUsd") ? d.get("drawnUsd").asDouble() : d.has("balanceUsd") ? d.get("balanceUsd").asDouble()
                                : d.has("notionalUsd") ? d.get("notionalUsd").asDouble() : Double.NaN));
                rows.forEach(r -> {
                    double want = amounts.get(r.get("productName").asText());
                    if (Double.isNaN(want)) assertThat(r.get("amountUsd").isNull()).isTrue();
                    else assertThat(num(r, "amountUsd")).isCloseTo(want, within(0.5));
                });
            }
            case "client-covenants" -> {
                List<JsonNode> cv = docs("covenants", Map.of("clientId", client));
                assertThat(rows.size()).isEqualTo(cv.size());
                List<Double> want = cv.stream().map(d -> d.get("headroomPct").asDouble()).sorted().toList();
                for (int i = 0; i < rows.size(); i++) {
                    assertThat(num(rows.get(i), "headroomPct")).isCloseTo(want.get(i), within(1e-9));
                    assertThat(rows.get(i).get("facility").asText()).isNotBlank();
                }
            }
            case "client-open-opportunities" -> {
                List<JsonNode> open = docs("opportunities", Map.of("clientId", client)).stream().filter(this::isOpen).toList();
                assertThat(rows.size()).isEqualTo(Math.min(15, open.size()));
                if (!open.isEmpty())
                    assertThat(num(rows.get(0), "expectedRevenueUsd")).isEqualTo(open.stream().mapToDouble(d -> d.get("expectedRevenueUsd").asDouble()).max().orElse(0));
            }
            case "client-active-signals" -> {
                List<JsonNode> active = docs("signals", Map.of("clientId", client)).stream()
                        .filter(d -> !d.get("stage").asText().equals("Resolved")).toList();
                assertThat(rows.size()).isEqualTo(Math.min(10, active.size()));
                String prev = "";
                for (JsonNode r : rows) {
                    assertThat(r.get("severity").asText()).isGreaterThanOrEqualTo(prev);
                    prev = r.get("severity").asText();
                }
            }
            case "client-contacts" -> {
                List<JsonNode> ct = docs("contacts", Map.of("clientId", client));
                assertThat(rows.size()).isEqualTo(ct.size());
                String primary = ct.stream().filter(d -> d.get("isPrimary").asBoolean()).findFirst().orElseThrow().get("name").asText();
                assertThat(rows.get(0).get("name").asText()).isEqualTo(primary);
            }
            case "book-revenue-by-lob-monthly" -> {
                // one row per month and line of business (the chart draws one line per line of business)
                assertThat(rows.size()).isEqualTo(24 * 4);
                Map<String, Map<String, Double>> byMonth = new HashMap<>();
                for (JsonNode r : rows) {
                    String m = r.get("month").asText();
                    // one find per month keeps each read under the server's 1000-document cap
                    Map<String, Double> byLob = byMonth.computeIfAbsent(m, k -> {
                        Map<String, Double> out = new HashMap<>();
                        docs("revenue_monthly", Map.of("month", Map.of("$date", k)))
                                .forEach(d -> out.merge(d.get("lob").asText(), d.get("totalUsd").asDouble(), Double::sum));
                        return out;
                    });
                    assertThat(num(r, "revenueUsd")).as(r.get("lob").asText() + " " + m).isCloseTo(byLob.getOrDefault(r.get("lob").asText(), 0.0), within(0.5));
                }
                assertThat(byMonth).hasSize(24);
            }
            default -> throw new AssertionError("no golden check for " + id);
        }
    }

    // ------------------------------------------------------------------ helpers
    private List<JsonNode> docs(String collection, Map<String, Object> filter) {
        SourceReader.Result r = source.find(collection, filter, null, null, 1000);
        assertThat(r.truncated()).as("golden read of " + collection + " truncated").isFalse();
        List<JsonNode> out = new ArrayList<>();
        r.documents().forEach(out::add);
        return out;
    }

    private boolean isOpen(JsonNode opp) { return !CLOSED.contains(opp.get("stage").asText()); }

    private static double sum(List<JsonNode> docs, String field, Predicate<JsonNode> when) {
        return docs.stream().filter(when).filter(d -> d.hasNonNull(field)).mapToDouble(d -> d.get(field).asDouble()).sum();
    }

    private static double num(JsonNode row, String field) { return row.get(field).asDouble(); }

    private static LocalDate month(JsonNode d) { return date(d.get("month").asText()); }

    private static LocalDate date(String iso) { return LocalDate.ofInstant(Instant.parse(iso), ZoneOffset.UTC); }
}
