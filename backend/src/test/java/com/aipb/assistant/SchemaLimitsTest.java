package com.aipb.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict schemas (output formats and strict tools) share per-request complexity limits; the API answers 400 above them.
 * Checked conservatively: each output schema plus every tool schema counted together, as if all were in one request.
 */
class SchemaLimitsTest {

    static final int MAX_UNION_PARAMS = 16;
    static final int MAX_OPTIONAL_PARAMS = 24;
    static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyStageStaysUnderTheStrictSchemaLimits() throws Exception {
        PathMatchingResourcePatternResolver r = new PathMatchingResourcePatternResolver();
        int[] tools = new int[2];
        for (Resource t : r.getResources("classpath:assistant/tools/*.json")) add(tools, count(read(t)));
        for (Resource s : r.getResources("classpath:assistant/schemas/*.json")) {
            int[] n = count(read(s));
            add(n, tools);
            assertTrue(n[0] <= MAX_UNION_PARAMS, s.getFilename() + " + tools: " + n[0] + " union-typed parameters (limit " + MAX_UNION_PARAMS + ")");
            assertTrue(n[1] <= MAX_OPTIONAL_PARAMS, s.getFilename() + " + tools: " + n[1] + " optional parameters (limit " + MAX_OPTIONAL_PARAMS + ")");
        }
    }

    /**
     * The design output has many fields; even under the published limits, optional fields made it "too complex" to
     * compile, so it keeps every field required and non-nullable (unused roles carry an unused value instead).
     */
    @Test
    void theDesignSchemaHasNoOptionalOrNullableFields() throws Exception {
        int[] n = count(read(new PathMatchingResourcePatternResolver().getResource("classpath:assistant/schemas/present.json")));
        assertTrue(n[0] == 0 && n[1] == 0, "present.json: " + n[0] + " union-typed and " + n[1] + " optional parameters");
    }

    private static JsonNode read(Resource res) throws Exception {
        try (InputStream in = res.getInputStream()) { return MAPPER.readTree(in); }
    }

    private static void add(int[] into, int[] n) { into[0] += n[0]; into[1] += n[1]; }

    /** [parameters using anyOf or a type array, properties not listed in their object's required]. */
    static int[] count(JsonNode node) {
        int[] n = new int[2];
        walk(node, n);
        return n;
    }

    private static void walk(JsonNode node, int[] n) {
        if (node.isObject()) {
            if (node.has("anyOf") || node.path("type").isArray()) n[0]++;
            JsonNode props = node.get("properties");
            if (props != null && props.isObject()) {
                Set<String> required = new HashSet<>();
                node.path("required").forEach(x -> required.add(x.asText()));
                props.fieldNames().forEachRemaining(f -> { if (!required.contains(f)) n[1]++; });
            }
        }
        node.forEach(child -> walk(child, n));
    }
}
