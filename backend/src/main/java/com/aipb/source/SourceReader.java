package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the source database, pinned to it. Two implementations: {@link DirectSource} (the MongoDB driver, used whenever
 * a page shows data) and {@link SourceData} (the MCP server, used by the assistant while it explores the data). Both
 * return plain JSON (see {@link Ejson}) and mark a result cut short by a limit as {@code truncated}.
 */
public interface SourceReader {

    Pattern COLLECTION = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,119}");

    /**
     * Documents returned by a query. {@code total} is how many matched (null if unknown); {@code limits} names the
     * limits that cut the result short, if any.
     */
    record Result(JsonNode documents, int count, Integer total, List<String> limits) {
        public boolean truncated() { return !limits.isEmpty(); }
    }

    String database();

    /** Runs an aggregation pipeline on a source collection. Write stages are refused. */
    Result aggregate(String collection, List<?> pipeline);

    /**
     * As {@link #aggregate(String, List)}, for a pipeline already in JSON form. Prefer this for stored specs: a JSON
     * tree is sent as-is, nulls included (e.g. {"$group": {"_id": null}}), whereas Maps lose null values to the app's
     * non-null JSON setting.
     */
    Result aggregate(String collection, ArrayNode pipeline);

    /** Finds documents in a source collection. Any argument but the collection may be null. */
    Result find(String collection, Map<String, Object> filter, Map<String, Object> projection, Map<String, Object> sort, Integer limit);

    /** The dataset's "today" (_meta.asOfDate) as an ISO instant. Every widget's time logic uses it. */
    String asOfDate();

    /** The collection name, if it is a valid name outside {@code system.*}. */
    static String collection(String name) {
        if (name == null || !COLLECTION.matcher(name).matches() || name.startsWith("system."))
            throw new IllegalArgumentException("invalid collection name '" + name + "'");
        return name;
    }
}
