package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The app's only gateway to the source data, through the MongoDB MCP server. Every call is pinned to the configured
 * source database, so nothing in the app (and later no assistant tool call) can read another database through it.
 *
 * <p>Two callers are planned: widgets reading their data at render time ({@link #aggregate}, {@link #find}), and the
 * assistant learning what data exists before it designs a widget ({@link #catalog}, {@link #collections},
 * {@link #schema}, plus samples via {@link #find}). Results are plain JSON (see {@link Ejson}); a result cut short by a
 * server limit is marked {@code truncated} so it is never shown as if it were complete.
 */
@Service
public class SourceData {

    /** The MCP server's connection to the database named in its configured connection string. */
    static final String CONNECTION = "preconfigured";
    private static final Pattern COLLECTION = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,119}");

    private static final long CACHE_MS = 60_000;

    private final McpClient mcp;
    private final String database;
    private volatile Cached<String> asOfDate;
    private volatile Cached<List<String>> collections;

    private record Cached<T>(T value, long at) {
        boolean fresh() { return System.currentTimeMillis() - at < CACHE_MS; }
    }

    public SourceData(McpClient mcp, @Value("${aipb.source.database}") String database) {
        this.mcp = mcp;
        this.database = database;
    }

    /**
     * Documents returned by a query. {@code total} is how many matched (null if the server did not say);
     * {@code limits} names the server limits that cut the result short, if any.
     */
    public record Result(JsonNode documents, int count, Integer total, List<String> limits) {
        public boolean truncated() { return !limits.isEmpty(); }
    }

    public String database() { return database; }

    /** Runs an aggregation pipeline on a source collection. Write stages are refused by the read-only server. */
    public Result aggregate(String collection, List<?> pipeline) {
        Map<String, Object> args = args(collection);
        args.put("pipeline", pipeline == null ? List.of() : pipeline);
        return result(mcp.callTool("aggregate", args));
    }

    /**
     * As {@link #aggregate(String, List)}, for a pipeline already in JSON form. Prefer this for stored specs: a JSON
     * tree is sent as-is, nulls included (e.g. {"$group": {"_id": null}}), whereas Maps lose null values to the app's
     * non-null JSON setting.
     */
    public Result aggregate(String collection, ArrayNode pipeline) {
        Map<String, Object> args = args(collection);
        args.put("pipeline", pipeline);
        return result(mcp.callTool("aggregate", args));
    }

    /** Finds documents in a source collection. Any argument but the collection may be null. */
    public Result find(String collection, Map<String, Object> filter, Map<String, Object> projection,
                       Map<String, Object> sort, Integer limit) {
        Map<String, Object> args = args(collection);
        if (filter != null) args.put("filter", filter);
        if (projection != null) args.put("projection", projection);
        if (sort != null) args.put("sort", sort);
        if (limit != null) args.put("limit", limit);
        return result(mcp.callTool("find", args));
    }

    /** The dataset's "today" (_meta.asOfDate) as an ISO instant; cached for a minute. Every widget's time logic uses it. */
    public String asOfDate() {
        Cached<String> c = asOfDate;
        if (c != null && c.fresh()) return c.value();
        JsonNode meta = meta();
        if (meta == null || !meta.hasNonNull("asOfDate")) throw new SourceException("the source database has no _meta.asOfDate");
        asOfDate = new Cached<>(meta.get("asOfDate").asText(), System.currentTimeMillis());
        return asOfDate.value();
    }

    /** As {@link #collections()}, cached for a minute. */
    public List<String> collectionsCached() {
        Cached<List<String>> c = collections;
        if (c != null && c.fresh()) return c.value();
        collections = new Cached<>(collections(), System.currentTimeMillis());
        return collections.value();
    }

    /** Names of the collections in the source database, sorted. */
    public List<String> collections() {
        Map<String, Object> args = new LinkedHashMap<>(Map.of("connectionId", CONNECTION, "database", database));
        JsonNode sc = mcp.callTool("list-collections", args).path("structuredContent");
        List<String> names = new ArrayList<>();
        sc.path("collections").forEach(c -> names.add(c.path("name").asText()));
        Collections.sort(names);
        return List.copyOf(names);
    }

    /** The data dictionary: every entry of {@code _catalog} (one per collection, plus the {@code _database} overview). */
    public JsonNode catalog() {
        return find("_catalog", null, null, Map.of("_id", 1), 1000).documents();
    }

    /** {@code _meta}: the dataset's asOfDate, generator version and counts, or null when absent. */
    public JsonNode meta() {
        JsonNode docs = find("_meta", null, null, null, 1).documents();
        return docs.isEmpty() ? null : docs.get(0);
    }

    /** The schema the server infers from a sample of a collection (field names and types). */
    public JsonNode schema(String collection) {
        return Ejson.simplify(mcp.callTool("collection-schema", args(collection)).path("structuredContent"));
    }

    // ------------------------------------------------------------------ helpers
    private Map<String, Object> args(String collection) {
        if (collection == null || !COLLECTION.matcher(collection).matches() || collection.startsWith("system."))
            throw new IllegalArgumentException("invalid collection name '" + collection + "'");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("connectionId", CONNECTION);
        args.put("database", database);
        args.put("collection", collection);
        return args;
    }

    private static Result result(JsonNode toolResult) {
        JsonNode sc = toolResult.path("structuredContent");
        if (!sc.has("documents")) throw new SourceException("unexpected tool result: " + McpClient.text(toolResult));
        JsonNode docs = Ejson.simplify(sc.get("documents"));
        List<String> limits = new ArrayList<>();
        sc.path("appliedLimits").forEach(l -> limits.add(l.asText()));
        // aggregate reports the full match count as "count", find as "queryResultsCount"
        JsonNode total = sc.has("queryResultsCount") ? sc.get("queryResultsCount") : sc.get("count");
        return new Result(docs, docs instanceof ArrayNode a ? a.size() : 0,
                total != null && total.canConvertToInt() ? total.asInt() : null, List.copyOf(limits));
    }
}
