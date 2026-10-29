package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

/**
 * The bank graph through the Neo4j Cypher MCP server, for the assistant learning what data exists before it designs a
 * widget ({@link #catalog}, {@link #labels}, {@link #schema}, samples and test queries via {@link #find} and
 * {@link #cypher}). Pages read their data with {@link GraphSource} instead, never through MCP.
 *
 * <p>Every query is checked before it is sent: {@link ReadOnlyCypher} on the text, then an {@code EXPLAIN} through the
 * backend's driver ({@link GraphSource#explain}) - the server's own read-only mode refuses data writes but lets schema
 * commands through, and the database refuses both. The server returns every record; results longer than the record
 * cap are cut to it and marked {@code truncated}, with their full count. Parameters travel as JSON, so dates are ISO
 * text: queries compare with {@code datetime($param)}, as the semantic layer's do.
 */
@Service
public class GraphMcpSource implements SourceReader {

    static final String READ_TOOL = "read_neo4j_cypher";
    private static final long CACHE_MS = 60_000;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final McpClient mcp;
    private final GraphSource graph;
    private final ObjectMapper mapper;
    private final String database;
    private final int maxRecords;
    private volatile Cached<String> asOfDate;
    private volatile Cached<List<String>> labels;

    private record Cached<T>(T value, long at) {
        boolean fresh() { return System.currentTimeMillis() - at < CACHE_MS; }
    }

    public GraphMcpSource(McpClient mcp, GraphSource graph, ObjectMapper mapper, @Value("${aipb.graph.database:neo4j}") String database,
                          @Value("${aipb.graph.max-records:1000}") int maxRecords) {
        this.mcp = mcp;
        this.graph = graph;
        this.mapper = mapper;
        this.database = database;
        this.maxRecords = maxRecords;
    }

    @Override
    public String database() { return database; }

    @Override
    public Result cypher(String query, Map<String, Object> params) {
        ReadOnlyCypher.check(query);
        Map<String, Object> p = params(params);
        if (graph != null) graph.explain(query, p);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", query);
        args.put("params", p);
        JsonNode rows = parse(McpClient.text(mcp.callTool(READ_TOOL, args)));
        Set<String> json = graph == null ? Set.of() : graph.dictionary().jsonProperties();
        ArrayNode out = F.arrayNode();
        for (int i = 0; i < Math.min(rows.size(), maxRecords); i++) out.add(GraphJson.simplify(rows.get(i), json));
        return new Result(out, out.size(), rows.size(), rows.size() > maxRecords ? List.of(MAX_RECORDS) : List.of());
    }

    @Override
    public Result find(String label, Map<String, Object> filter, List<String> fields, Map<String, Object> sort, Integer limit) {
        GraphSource.Compiled q = GraphSource.compileFind(graph == null ? SourceReader.label(label) : graph.knownLabel(label), filter, fields, sort,
                limit, maxRecords);
        Result r = cypher(q.cypher(), q.params());
        ArrayNode docs = F.arrayNode();
        for (JsonNode row : r.documents()) {
            ObjectNode d = (ObjectNode) row.get("n");
            d.properties().removeIf(e -> e.getValue().isNull());
            docs.add(d);
        }
        boolean ownLimit = limit != null && limit > 0 && limit <= maxRecords;
        return new Result(docs, docs.size(), ownLimit ? null : r.total(), ownLimit ? List.of() : r.limits());
    }

    /** Cached for a minute. */
    @Override
    public String asOfDate() {
        Cached<String> c = asOfDate;
        if (c != null && c.fresh()) return c.value();
        JsonNode meta = meta();
        if (meta == null || !meta.hasNonNull("asOfDate")) throw new SourceException("the bank graph has no (:_Meta).asOfDate");
        asOfDate = new Cached<>(meta.get("asOfDate").asText(), System.currentTimeMillis());
        return asOfDate.value();
    }

    /** The data dictionary: every (:_Catalog) entry (one per label, plus the {@code _database} overview), by id. */
    public JsonNode catalog() {
        ArrayNode out = F.arrayNode();
        cypher("MATCH (c:_Catalog) RETURN c.doc AS doc ORDER BY c.id", Map.of()).documents().forEach(d -> out.add(parse(d.path("doc").asText())));
        return out;
    }

    /** {@code (:_Meta)}: the dataset's asOfDate, generator version and counts, or null when absent. */
    public JsonNode meta() {
        JsonNode docs = cypher("MATCH (m:_Meta) RETURN m {.*} AS m LIMIT 1", Map.of()).documents();
        if (docs.isEmpty()) return null;
        ObjectNode m = (ObjectNode) docs.get(0).get("m");
        if (m.path("counts").isTextual()) m.set("counts", parse(m.get("counts").asText()));
        return m;
    }

    /** As {@link #labels()}, cached for a minute. */
    public List<String> labelsCached() {
        Cached<List<String>> c = labels;
        if (c != null && c.fresh()) return c.value();
        labels = new Cached<>(labels(), System.currentTimeMillis());
        return labels.value();
    }

    /** The data labels in the graph (not _Meta or _Catalog), sorted. */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        cypher("CALL db.labels() YIELD label RETURN label ORDER BY label", Map.of()).documents().forEach(d -> {
            String l = d.path("label").asText();
            if (!l.startsWith("_")) out.add(l);
        });
        return List.copyOf(out);
    }

    /**
     * What the graph holds for a label: each property with its stored types and whether every node has it, and each
     * relationship type to or from it, with the label at the other end and how many there are.
     */
    public JsonNode schema(String label) {
        String l = graph == null ? SourceReader.label(label) : graph.knownLabel(label);
        ObjectNode out = F.objectNode().put("label", l);
        out.set("properties", cypher("CALL db.schema.nodeTypeProperties() YIELD nodeLabels, propertyName, propertyTypes, mandatory "
                + "WHERE nodeLabels = [$label] AND propertyName IS NOT NULL "
                + "RETURN propertyName AS name, propertyTypes AS types, mandatory AS onEveryNode ORDER BY name", Map.of("label", l)).documents());
        out.set("relationships", cypher("MATCH (n:" + l + ")-[r]-(m) WITH type(r) AS type, CASE WHEN startNode(r) = n THEN 'out' ELSE 'in' END AS direction, "
                + "labels(m)[0] AS other, count(*) AS count RETURN type, direction, other, count ORDER BY type, other", Map.of()).documents());
        return out;
    }

    // ------------------------------------------------------------------ helpers
    /** Parameters as JSON values for the MCP server: dates as ISO text, numbers and text as themselves. */
    private Map<String, Object> params(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) in.forEach((k, v) -> out.put(k, v instanceof JsonNode n ? GraphJson.param(n)
                : v instanceof java.time.temporal.TemporalAccessor t ? GraphJson.INSTANT.format(t) : v));
        return out;
    }

    private JsonNode parse(String text) {
        try {
            JsonNode n = mapper.readTree(text);
            return n == null ? F.arrayNode() : n;
        } catch (IOException e) {
            throw new SourceException("unexpected tool result: " + text, e);
        }
    }
}
