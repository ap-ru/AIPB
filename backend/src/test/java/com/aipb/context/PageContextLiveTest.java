package com.aipb.context;

import com.aipb.seed.Defaults;
import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.SemanticModel;
import com.aipb.source.McpClient;
import com.aipb.source.SourceData;
import com.aipb.widget.SpecMigrator;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The starters that follow the page, on Command Center's context against the real source, checked with figures computed
 * independently from raw documents. Needs the running stack: {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
class PageContextLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SourceData source = new SourceData(
            new McpClient(mapper, System.getProperty("aipb.mcp-url", "http://localhost:3001/mcp"), 30), "bank");
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);
    private final ContextResolver resolver = new ContextResolver(sm, source);
    private final WidgetDataService data = new WidgetDataService(source, null, mapper, new QueryExecutor(sm, source));
    private final LocalDate asOf = LocalDate.ofInstant(Instant.parse(source.asOfDate()), ZoneOffset.UTC);

    private WidgetSpec starter(String id) throws Exception {
        return SpecMigrator.read(mapper, mapper.readTree(new ClassPathResource("widgets/starter/" + id + ".json").getInputStream()));
    }

    private WidgetDataService.DataResult onCommandCenter(String id, Map<String, String> url) throws Exception {
        var page = Defaults.page("command-center", "Command Center", "grid", "", true);
        page.setContext(Defaults.commandCenterContext());
        return data.execute(starter(id), resolver.resolve(page, Map.of(), url), null);
    }

    @Test
    void withoutAClientTheKpisAreTheWholeBook() throws Exception {
        List<JsonNode> credit = docs("holdings", Map.of("productType", "credit"));
        double drawn = credit.stream().mapToDouble(d -> d.get("drawnUsd").asDouble()).sum();
        WidgetDataService.DataResult r = onCommandCenter("client-exposure", Map.of());
        assertThat(r.rows().get(0).get("exposureUsd").asDouble()).isCloseTo(drawn, within(0.5));
        assertThat(r.context().scope()).isEqualTo("book");
        assertThat(r.context().period()).startsWith("current");
    }

    @Test
    void aPeriodAndARegionNarrowRevenueByLineOfBusiness() throws Exception {
        Set<String> emea = new HashSet<>();
        docs("clients", Map.of("region", "EMEA")).forEach(c -> emea.add(c.get("clientId").asText()));
        Map<String, Double> byLob = new HashMap<>();
        for (LocalDate m = asOf.withDayOfYear(1); m.isBefore(asOf.withDayOfMonth(1)); m = m.plusMonths(1))
            docs("revenue_monthly", Map.of("month", Map.of("$date", m.atStartOfDay().toInstant(ZoneOffset.UTC).toString()))).stream()
                    .filter(d -> emea.contains(d.get("clientId").asText()))
                    .forEach(d -> byLob.merge(d.get("lob").asText(), d.get("totalUsd").asDouble(), Double::sum));
        WidgetDataService.DataResult r = onCommandCenter("client-revenue-by-lob", Map.of("period", "ytd", "region", "EMEA"));
        assertThat(r.rows()).hasSize(byLob.size());
        r.rows().forEach(row -> assertThat(row.get("revenueUsd").asDouble()).isCloseTo(byLob.get(row.get("lob").asText()), within(0.5)));
        assertThat(r.context().filters()).containsExactly("Region: EMEA");
        assertThat(r.context().period()).startsWith("calendar year to date");
    }

    @Test
    void theBookTrendFollowsThePagePeriod() throws Exception {
        WidgetDataService.DataResult r = onCommandCenter("book-revenue-by-lob-monthly", Map.of("period", "l4q"));
        Set<String> months = new TreeSet<>();
        r.rows().forEach(row -> months.add(row.get("month").asText()));
        assertThat(months).hasSize(12);
        assertThat(LocalDate.ofInstant(Instant.parse(months.iterator().next()), ZoneOffset.UTC))
                .isEqualTo(asOf.withDayOfMonth(1).withMonth((asOf.getMonthValue() - 1) / 3 * 3 + 1).minusMonths(12));
    }

    @Test
    void aFilterAWidgetCannotTakeIsReportedNotIgnoredSilently() throws Exception {
        WidgetDataService.DataResult r = onCommandCenter("client-deposits", Map.of("lob", "Capital Markets"));
        assertThat(r.context().filters()).containsExactly("Line of business: Capital Markets");
        WidgetDataService.DataResult rm = onCommandCenter("client-deposits", Map.of("relationshipManager", resolver.filterOptions("relationshipManager").get(0)));
        assertThat(rm.context().filters()).hasSize(1);
        assertThat(rm.notes()).isEmpty();
    }

    private List<JsonNode> docs(String collection, Map<String, Object> filter) {
        SourceData.Result r = source.find(collection, filter, null, null, 1000);
        assertThat(r.truncated()).isFalse();
        List<JsonNode> out = new ArrayList<>();
        r.documents().forEach(out::add);
        return out;
    }
}
