package com.aipb.parity;

import com.aipb.semantic.QueryContext;
import com.aipb.semantic.QueryExecutor;
import com.aipb.semantic.ResultSet;
import com.aipb.semantic.SemanticModel;
import com.aipb.source.DirectSource;
import com.aipb.support.LiveSources;
import com.aipb.widget.SpecMigrator;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Captures the parity fixtures from the MongoDB stack (see {@link ParityFixtures}): run once, on the Mongo line, with
 * the bank seeded at a pinned BANK_AS_OF. Writes src/test/resources/graph-parity/{starters,queries}.json.
 * {@code mvn test -Dgroups=capture -Dsurefire.excludedGroups= -Dtest=CaptureParityFixturesTest}
 */
@Tag("capture")
class CaptureParityFixturesTest {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final DirectSource direct = LiveSources.direct(mapper);
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);
    private final QueryExecutor executor = new QueryExecutor(sm, direct);
    private final WidgetDataService data = new WidgetDataService(direct, null, mapper, executor);

    @Test
    void capture() throws Exception {
        String asOf = direct.asOfDate();
        List<String> clients = new ArrayList<>();
        direct.find("clients", null, Map.of("clientId", 1), Map.of("clientId", 1), 1000).documents().forEach(c -> clients.add(c.get("clientId").asText()));
        assertThat(clients).hasSize(60);
        Path dir = Path.of("src/test/resources", ParityFixtures.DIR);
        Files.createDirectories(dir);

        ObjectNode starters = mapper.createObjectNode().put("asOfDate", asOf).put("source", "mongodb");
        ArrayNode runs = starters.putArray("runs");
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath:widgets/starter/*.json");
        List<String> ids = new ArrayList<>();
        for (var r : resources) ids.add(Objects.requireNonNull(r.getFilename()).replace(".json", ""));
        Collections.sort(ids);
        for (String id : ids) {
            WidgetSpec spec = SpecMigrator.read(mapper, mapper.readTree(new ClassPathResource("widgets/starter/" + id + ".json").getInputStream()));
            boolean book = "book".equals(spec.data().query().scopeOrDefault());
            for (String c : book ? Collections.<String>singletonList(null) : clients) {
                WidgetDataService.DataResult res = data.execute(spec, c == null ? Map.of() : Map.of("clientId", c));
                assertThat(res.truncated()).as(id + " " + c).isFalse();
                ObjectNode run = runs.addObject().put("starter", id);
                if (c != null) run.put("clientId", c);
                run.set("columns", mapper.valueToTree(res.columns().stream().map(ResultSet.Column::name).toList()));
                run.set("rows", res.rows());
            }
        }
        Files.writeString(dir.resolve("starters.json"), mapper.writeValueAsString(starters));

        ObjectNode queries = mapper.createObjectNode().put("asOfDate", asOf).put("source", "mongodb");
        ArrayNode qruns = queries.putArray("runs");
        LocalDate day = LocalDate.ofInstant(Instant.parse(asOf), ZoneOffset.UTC);
        int ok = 0;
        for (ParityFixtures.Battery b : ParityFixtures.battery(sm)) {
            for (String c : b.perClient() ? ParityFixtures.CLIENTS_FOR_QUERIES : Collections.<String>singletonList(null)) {
                ObjectNode run = qruns.addObject().put("name", b.name());
                if (c != null) run.put("clientId", c);
                run.set("query", mapper.valueToTree(b.query()));
                try {
                    ResultSet r = executor.execute(b.query(), new QueryContext(day, c));
                    assertThat(r.truncated()).as(b.name()).isFalse();
                    run.set("rows", r.rows());
                    run.put("hasMore", r.hasMore());
                    ok++;
                } catch (RuntimeException e) {
                    run.put("error", String.valueOf(e.getMessage()));
                }
            }
        }
        Files.writeString(dir.resolve("queries.json"), mapper.writeValueAsString(queries));
        assertThat(ok).isGreaterThan(100);
    }

    @SuppressWarnings("unused")
    private JsonNode read(String path) throws Exception { return mapper.readTree(new ClassPathResource(path).getInputStream()); }
}
