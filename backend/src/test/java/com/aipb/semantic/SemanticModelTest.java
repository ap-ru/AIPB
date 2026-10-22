package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shipped semantic model loads, is structurally sound, and agrees with the data dictionary (data/catalog.json). */
class SemanticModelTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SemanticModel sm = SemanticModel.fromClasspath(mapper);

    @Test
    void loadsWithoutStructuralProblems() {
        assertThat(SemanticModelValidator.structural(sm)).isEmpty();
        assertThat(sm.models()).containsKeys("clients", "revenue", "holdings", "opportunities", "covenants", "signals", "contacts", "activities");
        assertThat(sm.isMetric("revenueUsd")).isTrue();
        assertThat(sm.isMetric("utilization")).isTrue();
        assertThat(sm.fingerprint()).hasSize(64);
    }

    @Test
    void agreesWithTheDataDictionary() throws Exception {
        JsonNode catalog = mapper.readTree(Files.readString(Path.of("../data/catalog.json")));
        assertThat(SemanticModelValidator.againstCatalog(sm, catalog)).isEmpty();
    }

    @Test
    void clientOwnedModelsInheritTheEntityDimensions() {
        Model revenue = sm.model("revenue");
        assertThat(revenue.joins()).containsKey("client");
        assertThat(revenue.dimensions().get("region").field()).isEqualTo("client.region");
        assertThat(revenue.dimensions().get("client")).isEqualTo(new Model.Binding("clientId", "client.name"));
        assertThat(sm.model("clients").joins()).doesNotContainKey("client");
    }

    @Test
    void catalogCheckCatchesRenamedFieldsAndValues() throws Exception {
        JsonNode catalog = mapper.readTree(Files.readString(Path.of("../data/catalog.json")));
        for (JsonNode e : catalog) {
            if (e.path("_id").asText().equals("holdings")) {
                for (JsonNode f : e.get("fields")) {
                    if (f.path("name").asText().equals("drawnUsd")) ((com.fasterxml.jackson.databind.node.ObjectNode) f).put("name", "drawnAmountUsd");
                    if (f.path("name").asText().equals("productType"))
                        ((com.fasterxml.jackson.databind.node.ArrayNode) f.get("allowedValues")).remove(0); // drop "credit"
                }
            }
        }
        List<String> problems = SemanticModelValidator.againstCatalog(sm, catalog);
        assertThat(problems).anyMatch(p -> p.contains("measure exposureUsd") && p.contains("drawnUsd"));
        assertThat(problems).anyMatch(p -> p.contains("segment credit") && p.contains("'credit'"));
    }

    @Test
    void structuralCheckCatchesBrokenDefinitions() throws Exception {
        JsonNode dims = mapper.readTree("{\"dimensions\": [{\"id\": \"lob\", \"label\": \"LOB\", \"type\": \"string\"}]}");
        JsonNode metrics = mapper.readTree("""
                {"metrics": [
                  {"id": "a", "kind": "ratio", "numerator": "b", "denominator": "x", "unit": "ratio", "description": "a"},
                  {"id": "b", "kind": "same", "of": "a", "unit": "ratio", "description": "b"},
                  {"id": "c", "kind": "difference", "left": "x", "right": "y", "unit": "USD", "description": "c"}
                ]}""");
        JsonNode model = mapper.readTree("""
                {"id": "m", "collection": "facts", "time": null,
                 "dimensions": {"lob": "lob", "region": "client.region"},
                 "measures": [
                   {"id": "x", "agg": "sum", "field": "amountUsd", "unit": "USD", "description": "x"},
                   {"id": "y", "agg": "count", "unit": "count", "description": "y"},
                   {"id": "z", "agg": "count", "field": "f", "unit": "furlongs", "segments": ["nope"], "description": ""}
                 ]}""");
        SemanticModel broken = SemanticModel.parse(mapper, dims, metrics, List.of(model), "test");
        List<String> problems = SemanticModelValidator.structural(broken);
        assertThat(problems).anyMatch(p -> p.contains("unknown dimension 'region'"));
        assertThat(problems).anyMatch(p -> p.contains("count takes neither field nor expr"));
        assertThat(problems).anyMatch(p -> p.contains("unit 'furlongs'"));
        assertThat(problems).anyMatch(p -> p.contains("unknown segment 'nope'"));
        assertThat(problems).anyMatch(p -> p.contains("needs a description"));
        assertThat(problems).anyMatch(p -> p.contains("metric cycle"));
        assertThat(problems).anyMatch(p -> p.contains("difference of USD and count"));
    }
}
