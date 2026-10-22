package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

/**
 * Flattens MongoDB Extended JSON (as the MCP server returns it) into plain JSON a widget can render:
 * {@code {"$date": ...}} becomes an ISO-8601 string, {@code $numberLong / $numberInt / $numberDouble / $numberDecimal}
 * become numbers and {@code {"$oid": ...}} becomes its hex string.
 */
public final class Ejson {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Ejson() {}

    public static JsonNode simplify(JsonNode node) {
        if (node == null) return F.nullNode();
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(n -> out.add(simplify(n)));
            return out;
        }
        if (!node.isObject()) return node;
        if (node.size() == 1) {
            Map.Entry<String, JsonNode> e = node.fields().next();
            JsonNode v = e.getValue();
            switch (e.getKey()) {
                case "$date" -> {
                    if (v.isTextual()) return F.textNode(v.asText());
                    long ms = v.isObject() ? Long.parseLong(v.path("$numberLong").asText("0")) : v.asLong();
                    return F.textNode(Instant.ofEpochMilli(ms).toString());
                }
                case "$numberLong" -> { return F.numberNode(Long.parseLong(v.asText())); }
                case "$numberInt" -> { return F.numberNode(Integer.parseInt(v.asText())); }
                case "$numberDouble" -> {
                    String s = v.asText();
                    return s.matches("-?Infinity|NaN") ? F.textNode(s) : F.numberNode(Double.parseDouble(s));
                }
                case "$numberDecimal" -> { return F.numberNode(new BigDecimal(v.asText())); }
                case "$oid" -> { return F.textNode(v.asText()); }
                default -> { }
            }
        }
        ObjectNode out = F.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            out.set(e.getKey(), simplify(e.getValue()));
        }
        return out;
    }
}
