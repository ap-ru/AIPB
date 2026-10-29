package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * One test on a field: {@code field op value}. Segments are lists of conditions, and query filters compile to them.
 * A condition renders as a Cypher predicate ({@link #toCypher}), used both to filter nodes (WHERE) and to count
 * conditionally inside an aggregation (CASE WHEN, e.g. exposure = drawn over credit holdings only). Missing properties
 * behave as they did in MongoDB: a missing value is "not equal" and "not in" (ne, nin hold), and fails every other
 * test (eq, in, and the range tests, which compare with null and so are never true).
 */
public record Condition(String field, String op, JsonNode value) {

    public static final Set<String> OPS = Set.of("eq", "ne", "in", "nin", "gt", "gte", "lt", "lte");

    /** As a predicate on {@code ref} (an already-safe property expression, e.g. {@code n.status}) against the parameter {@code $param}. */
    public String toCypher(String ref, String param) {
        String p = "$" + param;
        return switch (op) {
            case "eq" -> ref + " = " + p;
            case "ne" -> "(" + ref + " IS NULL OR " + ref + " <> " + p + ")";
            case "in" -> ref + " IN " + p;
            case "nin" -> "(" + ref + " IS NULL OR NOT " + ref + " IN " + p + ")";
            case "gt" -> ref + " > " + p;
            case "gte" -> ref + " >= " + p;
            case "lt" -> ref + " < " + p;
            case "lte" -> ref + " <= " + p;
            default -> throw new IllegalStateException("unchecked op " + op);
        };
    }

    /** {@code a AND b ...} of several predicates, or the one predicate itself. */
    public static String and(List<String> predicates) {
        return predicates.size() == 1 ? predicates.get(0) : "(" + String.join(" AND ", predicates) + ")";
    }

    /** The values the condition compares with, for checks against a dimension's allowed values. */
    public List<String> textValues() {
        if (value == null) return List.of();
        if (value.isArray()) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            value.forEach(v -> out.add(v.asText()));
            return out;
        }
        return List.of(value.asText());
    }

    @Override
    public String toString() { return field + " " + op + " " + value; }
}
