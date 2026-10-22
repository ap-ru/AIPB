package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

/**
 * One test on a field: {@code field op value}. Segments are lists of conditions, and query filters compile to them.
 * A condition renders two ways that agree on missing fields: as a $match query (to filter documents) and as an
 * aggregation expression (to count conditionally inside $group, e.g. exposure = drawn over credit holdings only).
 */
public record Condition(String field, String op, JsonNode value) {

    public static final Set<String> OPS = Set.of("eq", "ne", "in", "nin", "gt", "gte", "lt", "lte");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** As a query fragment on {@code path}: {path: {$op: value}}. */
    public ObjectNode toMatch(String path) {
        ObjectNode q = F.objectNode();
        q.putObject(path).set("$" + op, value.deepCopy());
        return q;
    }

    /**
     * As an aggregation expression on {@code path}. Range tests are guarded with {$gt: [path, null]} so a missing field
     * fails them, as it does in a $match (in expressions null sorts below every number and date).
     */
    public JsonNode toExpr(String path) {
        String ref = "$" + path;
        return switch (op) {
            case "in" -> F.objectNode().set("$in", F.arrayNode().add(ref).add(value.deepCopy()));
            case "nin" -> F.objectNode().set("$not", F.arrayNode().add(F.objectNode().set("$in", F.arrayNode().add(ref).add(value.deepCopy()))));
            case "gt", "gte", "lt", "lte" -> and(List.of(
                    F.objectNode().set("$gt", F.arrayNode().add(ref).addNull()),
                    F.objectNode().set("$" + op, F.arrayNode().add(ref).add(value.deepCopy()))));
            default -> F.objectNode().set("$" + op, F.arrayNode().add(ref).add(value.deepCopy()));
        };
    }

    /** {$and: [...]} of several expressions, or the one expression itself. */
    public static JsonNode and(List<JsonNode> exprs) {
        if (exprs.size() == 1) return exprs.get(0);
        ArrayNode all = F.arrayNode();
        exprs.forEach(all::add);
        return F.objectNode().set("$and", all);
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
