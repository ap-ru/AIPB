package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.bson.BsonValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Map;

/**
 * Flattens MongoDB Extended JSON (as the MCP server returns it) into plain JSON a widget can render:
 * {@code {"$date": ...}} becomes an ISO-8601 string, {@code $numberLong / $numberInt / $numberDouble / $numberDecimal}
 * become numbers and {@code {"$oid": ...}} becomes its hex string. {@link #fromBson} does the same for documents read
 * with the driver, so both sources return identical JSON.
 */
public final class Ejson {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** As the MCP server writes dates: always with milliseconds. */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

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

    /** A BSON value as plain JSON, with the same flattening as {@link #simplify}. */
    public static JsonNode fromBson(BsonValue v) {
        if (v == null) return F.nullNode();
        return switch (v.getBsonType()) {
            case DOCUMENT -> {
                ObjectNode out = F.objectNode();
                v.asDocument().forEach((k, x) -> out.set(k, fromBson(x)));
                yield out;
            }
            case ARRAY -> {
                ArrayNode out = F.arrayNode();
                v.asArray().forEach(x -> out.add(fromBson(x)));
                yield out;
            }
            case STRING -> F.textNode(v.asString().getValue());
            case BOOLEAN -> F.booleanNode(v.asBoolean().getValue());
            case INT32 -> F.numberNode(v.asInt32().getValue());
            case INT64 -> F.numberNode(v.asInt64().getValue());
            case DOUBLE -> {
                double d = v.asDouble().getValue();
                yield Double.isFinite(d) ? F.numberNode(d) : F.textNode(Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
            }
            case DECIMAL128 -> v.asDecimal128().getValue().isFinite()
                    ? F.numberNode(v.asDecimal128().getValue().bigDecimalValue()) : F.textNode(v.asDecimal128().getValue().toString());
            case DATE_TIME -> F.textNode(DATE.format(Instant.ofEpochMilli(v.asDateTime().getValue())));
            case OBJECT_ID -> F.textNode(v.asObjectId().getValue().toHexString());
            case NULL, UNDEFINED -> F.nullNode();
            default -> F.textNode(v.toString());
        };
    }
}
