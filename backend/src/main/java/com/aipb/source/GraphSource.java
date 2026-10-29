package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.summary.QueryType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bank graph read with the Neo4j driver: what every page shows (widget data, page parameter choices, filter
 * values) comes through here, not through the MCP server. It is the {@link Primary} {@link SourceReader}, so anything
 * that asks for a reader gets this one; the assistant asks for {@link GraphMcpSource} by name.
 *
 * <p>Read-only three ways: every query passes {@link ReadOnlyCypher} and an {@code EXPLAIN} that must plan as
 * read-only, it runs in a read transaction, and the stack opens the database read-only. Every call is pinned to
 * {@code aipb.graph.database}, bounded in time ({@code max-time-ms}) and in records ({@code max-records}): a result cut
 * short by the cap is marked {@code truncated}, with its full count.
 */
@Slf4j
@Primary
@Service
public class GraphSource implements SourceReader {

    private static final long CACHE_MS = 60_000;
    private static final int MAX_EXPLAINED = 2_000;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final Driver driver;
    private final String database;
    private final int maxRecords;
    private final Duration maxTime;
    private final ObjectMapper mapper;
    /** Query texts that planned as read-only: the semantic layer sends the same few shapes again and again. */
    private final Set<String> explained = ConcurrentHashMap.newKeySet();
    private volatile Dictionary dictionary;
    private volatile String asOfDate;
    private volatile long asOfAt;

    /** What the readers need from the data dictionary ((:_Catalog)): the labels, and the properties stored as JSON. */
    public record Dictionary(JsonNode entries, Set<String> labels, Set<String> jsonProperties, long at) {
        boolean fresh() { return System.currentTimeMillis() - at < CACHE_MS; }
    }

    public GraphSource(ObjectMapper mapper, @Value("${aipb.graph.uri}") String uri, @Value("${aipb.graph.user}") String user,
                       @Value("${aipb.graph.password}") String password, @Value("${aipb.graph.database:neo4j}") String database,
                       @Value("${aipb.graph.max-records:1000}") int maxRecords, @Value("${aipb.graph.max-time-ms:10000}") long maxTimeMs) {
        this.mapper = mapper;
        this.database = database;
        this.maxRecords = maxRecords;
        this.maxTime = Duration.ofMillis(maxTimeMs);
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password), Config.builder()
                .withConnectionTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .withConnectionAcquisitionTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build());
        log.info("Source data read directly from {} / {}", uri, database);
    }

    @PreDestroy
    void close() { driver.close(); }

    @Override
    public String database() { return database; }

    @Override
    public Result cypher(String query, Map<String, Object> params) {
        ReadOnlyCypher.check(query);
        Map<String, Object> p = params(params);
        explain(query, p);
        Set<String> json = dictionary().jsonProperties();
        return read(tx -> {
            org.neo4j.driver.Result r = tx.run(query, p);
            ArrayNode out = F.arrayNode();
            int n = 0;
            while (r.hasNext()) {
                org.neo4j.driver.Record rec = r.next();
                if (n++ < maxRecords) out.add(row(rec, json));
            }
            return new Result(out, out.size(), n, n > maxRecords ? List.of(MAX_RECORDS) : List.of());
        });
    }

    /**
     * Refuses a query that does not plan as read-only. Runs {@code EXPLAIN} (which plans the query without running it)
     * in a read transaction; a query text that passed once is remembered.
     */
    public void explain(String query, Map<String, Object> params) {
        if (explained.contains(query)) return;
        QueryType type = read(tx -> tx.run("EXPLAIN " + query, params).consume().queryType());
        if (type != QueryType.READ_ONLY)
            throw new SourceException("the source is read-only: the query plans as " + type.name().toLowerCase(Locale.ROOT).replace('_', '-'));
        if (explained.size() < MAX_EXPLAINED) explained.add(query);
    }

    @Override
    public Result find(String label, Map<String, Object> filter, List<String> fields, Map<String, Object> sort, Integer limit) {
        Compiled q = compileFind(knownLabel(label), filter, fields, sort, limit, maxRecords);
        Result r = cypher(q.cypher(), q.params());
        ArrayNode docs = F.arrayNode();
        for (JsonNode row : r.documents()) {
            ObjectNode d = (ObjectNode) row.get("n");
            // a projection returns absent properties as null; leave them out, as a node would
            if (fields != null) d.properties().removeIf(e -> e.getValue().isNull());
            docs.add(d);
        }
        boolean ownLimit = limit != null && limit > 0 && limit <= maxRecords;
        return new Result(docs, docs.size(), ownLimit ? null : r.total(), ownLimit ? List.of() : r.limits());
    }

    /** A find as parameterized Cypher; property names are validated, every value is a parameter. */
    public record Compiled(String cypher, Map<String, Object> params) {}

    static Compiled compileFind(String label, Map<String, Object> filter, List<String> fields, Map<String, Object> sort, Integer limit,
                                int maxRecords) {
        StringBuilder q = new StringBuilder("MATCH (n:").append(SourceReader.label(label)).append(')');
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> where = new ArrayList<>();
        if (filter != null) {
            int i = 0;
            for (Map.Entry<String, Object> e : filter.entrySet()) {
                String prop = "n." + SourceReader.property(e.getKey());
                Object v = e.getValue();
                if (v instanceof Map<?, ?> ops) {
                    for (Map.Entry<?, ?> op : ops.entrySet()) {
                        String p = "f" + i++;
                        params.put(p, plain(op.getValue()));
                        where.add(switch (String.valueOf(op.getKey())) {
                            case "$in" -> prop + " IN $" + p;
                            case "$nin" -> "(" + prop + " IS NULL OR NOT " + prop + " IN $" + p + ")";
                            case "$ne" -> "(" + prop + " IS NULL OR " + prop + " <> $" + p + ")";
                            case "$eq" -> prop + " = $" + p;
                            default -> throw new IllegalArgumentException("filter operator " + op.getKey() + " is not supported (use $in, $nin, $ne)");
                        });
                    }
                } else if (v instanceof Collection<?>) {
                    String p = "f" + i++;
                    params.put(p, plain(v));
                    where.add(prop + " IN $" + p);
                } else {
                    String p = "f" + i++;
                    params.put(p, plain(v));
                    where.add(v == null ? prop + " IS NULL" : prop + " = $" + p);
                    if (v == null) params.remove(p);
                }
            }
        }
        if (!where.isEmpty()) q.append(" WHERE ").append(String.join(" AND ", where));
        if (fields == null) q.append(" RETURN n");  // read back as the document
        else {
            LinkedHashSet<String> props = new LinkedHashSet<>();
            props.add("_id");
            fields.forEach(f -> props.add(SourceReader.property(f)));
            StringJoiner j = new StringJoiner(", ", " RETURN n {", "} AS n");
            props.forEach(p -> j.add("." + p));
            q.append(j);
        }
        if (sort != null && !sort.isEmpty()) {
            StringJoiner j = new StringJoiner(", ", " ORDER BY ", "");
            sort.forEach((k, dir) -> j.add("n." + SourceReader.property(k) + (Integer.parseInt(String.valueOf(dir)) < 0 ? " DESC" : "")));
            q.append(j);
        }
        boolean ownLimit = limit != null && limit > 0 && limit <= maxRecords;
        if (ownLimit) {
            q.append(" LIMIT $limit");
            params.put("limit", limit.longValue());
        }
        return new Compiled(q.toString(), params);
    }

    /** Cached for a minute. */
    @Override
    public String asOfDate() {
        if (asOfDate != null && System.currentTimeMillis() - asOfAt < CACHE_MS) return asOfDate;
        JsonNode docs = cypher("MATCH (m:_Meta) RETURN m.asOfDate AS asOfDate LIMIT 1", Map.of()).documents();
        if (docs.isEmpty() || !docs.get(0).hasNonNull("asOfDate")) throw new SourceException("the bank graph has no (:_Meta).asOfDate");
        asOfDate = docs.get(0).get("asOfDate").asText();
        asOfAt = System.currentTimeMillis();
        return asOfDate;
    }

    /** {@code (:_Meta)}: the dataset's asOfDate, generator version and counts, or null when absent. */
    public JsonNode meta() {
        JsonNode docs = find("_Meta", null, null, null, 1).documents();
        if (docs.isEmpty()) return null;
        ObjectNode m = (ObjectNode) docs.get(0);
        if (m.path("counts").isTextual()) m.set("counts", readTree(m.get("counts").asText()));
        return m;
    }

    /** The labels in the graph, sorted. */
    public List<String> labels() {
        List<String> out = new ArrayList<>();
        cypher("CALL db.labels() YIELD label RETURN label ORDER BY label", Map.of()).documents().forEach(d -> out.add(d.get("label").asText()));
        return List.copyOf(out);
    }

    /** The data dictionary, cached for a minute: every (:_Catalog) entry (one per label, plus the _database overview). */
    public Dictionary dictionary() {
        Dictionary d = dictionary;
        if (d != null && d.fresh()) return d;
        ArrayNode entries = F.arrayNode();
        Set<String> labels = new TreeSet<>(), json = new TreeSet<>();
        read(tx -> {
            tx.run("MATCH (c:_Catalog) RETURN c.doc AS doc ORDER BY c.id").forEachRemaining(r -> entries.add(readTree(r.get("doc").asString())));
            return null;
        });
        for (JsonNode e : entries) {
            if (!e.has("properties")) continue;
            labels.add(e.path("_id").asText());
            e.path("properties").forEach(p -> { if ("json".equals(p.path("storage").asText())) json.add(p.path("name").asText()); });
        }
        labels.addAll(List.of("_Meta", "_Catalog"));
        dictionary = new Dictionary(entries, Set.copyOf(labels), Set.copyOf(json), System.currentTimeMillis());
        return dictionary;
    }

    /** The label, if the dictionary has it (or the dictionary cannot be read yet). */
    String knownLabel(String label) {
        SourceReader.label(label);
        Set<String> labels = dictionary().labels();
        if (labels.size() > 2 && !labels.contains(label))
            throw new IllegalArgumentException("unknown label '" + label + "'; labels: " + String.join(", ", new TreeSet<>(labels)));
        return label;
    }

    // ------------------------------------------------------------------ helpers
    private <T> T read(TransactionCallback<T> work) {
        try (Session s = driver.session(SessionConfig.builder().withDatabase(database).withDefaultAccessMode(AccessMode.READ).build())) {
            return s.executeRead(work, TransactionConfig.builder().withTimeout(maxTime).withMetadata(Map.of("app", "aipb")).build());
        } catch (ServiceUnavailableException e) {
            throw new SourceException("source database unreachable: " + e.getMessage(), e);
        } catch (ClientException e) {
            if (String.valueOf(e.code()).contains("TransactionTimedOut"))
                throw new SourceException("the query took too long and was stopped", e);
            throw new SourceException("source query failed: " + e.getMessage(), e);
        } catch (Neo4jException e) {
            throw new SourceException("source query failed: " + e.getMessage(), e);
        }
    }

    private static ObjectNode row(org.neo4j.driver.Record rec, Set<String> json) {
        ObjectNode o = F.objectNode();
        for (String k : rec.keys()) o.set(k, GraphJson.property(k, rec.get(k), json));
        return o;
    }

    /** Parameters for the driver: JSON trees become plain values; everything else is passed as is. */
    private static Map<String, Object> params(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in != null) in.forEach((k, v) -> out.put(k, plain(v)));
        return out;
    }

    private static Object plain(Object v) {
        if (v instanceof JsonNode n) return GraphJson.param(n);
        if (v instanceof Collection<?> c) return c.stream().map(GraphSource::plain).toList();
        if (v instanceof Integer i) return i.longValue();
        return v;
    }

    private JsonNode readTree(String text) {
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new SourceException("unreadable dictionary entry: " + e.getMessage(), e);
        }
    }
}
