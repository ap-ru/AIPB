package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the bank graph (Neo4j), read-only. Two implementations: {@link GraphSource} (the Neo4j driver, used whenever a
 * page shows data) and {@link GraphMcpSource} (the Cypher MCP server, used by the assistant while it explores the
 * data). Both refuse anything but a read ({@link ReadOnlyCypher}), return plain JSON (see {@link GraphJson}) and mark a
 * result cut short by the record cap as {@code truncated}.
 */
public interface SourceReader {

    Pattern LABEL = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    Pattern PROPERTY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    /** The limit name reported when the record cap cuts a result short. */
    String MAX_RECORDS = "maxRecordsPerQuery";

    /**
     * Records returned by a query, as JSON objects (one per record, keyed by column). {@code total} is how many there
     * were (null if unknown); {@code limits} names the limits that cut the result short, if any.
     */
    record Result(JsonNode documents, int count, Integer total, List<String> limits) {
        public boolean truncated() { return !limits.isEmpty(); }
    }

    /** The Neo4j database the reader is pinned to. */
    String database();

    /** Runs a read-only Cypher query with parameters (values only: the query text is never assembled from them). */
    Result cypher(String query, Map<String, Object> params);

    /**
     * Nodes of one label as property maps. {@code filter}: property to value (equality), or to {@code {"$in": [...]}},
     * {@code {"$nin": [...]}} or {@code {"$ne": x}} (a missing property is "not equal", as in MongoDB); {@code fields}:
     * the properties to return (null: all, and {@code _id} always); {@code sort}: property to 1 or -1, in order. Any
     * argument but the label may be null.
     */
    Result find(String label, Map<String, Object> filter, List<String> fields, Map<String, Object> sort, Integer limit);

    /** The dataset's "today" ((:_Meta).asOfDate) as an ISO instant. Every widget's time logic uses it. */
    String asOfDate();

    /** The label, if it is a valid label name. */
    static String label(String name) {
        if (name == null || !LABEL.matcher(name).matches()) throw new IllegalArgumentException("invalid label '" + name + "'");
        return name;
    }

    /** The property name, if it is a valid property name. */
    static String property(String name) {
        if (name == null || !PROPERTY.matcher(name).matches()) throw new IllegalArgumentException("invalid property name '" + name + "'");
        return name;
    }
}
