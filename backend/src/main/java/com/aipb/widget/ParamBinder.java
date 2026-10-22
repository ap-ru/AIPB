package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Fills a custom spec's parameters into its pipeline. A parameter is referenced as the object {"$param": "name"} and is
 * replaced by a single JSON value - never spliced into text - so a value can't change the query's structure. Dates
 * become Extended JSON {"$date": ...}, so they compare as BSON dates (a plain string date would silently match nothing).
 */
public final class ParamBinder {

    public static final String PARAM_KEY = "$param";
    public static final String AS_OF_DATE = "asOfDate";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ParamBinder() {}

    /** The bound pipeline, and the value each parameter resolved to (as given, after type checks). */
    public record Bound(JsonNode pipeline, Map<String, String> values) {}

    /**
     * Resolves every declared parameter from {@code given} (asOfDate falls back to {@code asOfDate}), type-checks it
     * and substitutes it. Throws IllegalArgumentException naming what is missing or malformed.
     */
    public static Bound bind(WidgetSpec spec, Map<String, String> given, Supplier<String> asOfDate) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (WidgetSpec.Param p : spec.paramsOrEmpty()) {
            String raw = given == null ? null : given.get(p.name());
            if ((raw == null || raw.isBlank()) && AS_OF_DATE.equals(p.name())) raw = asOfDate.get();
            if (raw == null || raw.isBlank()) { missing.add(p.name()); continue; }
            nodes.put(p.name(), convert(p, raw.trim()));
            values.put(p.name(), raw.trim());
        }
        if (!missing.isEmpty())
            throw new IllegalArgumentException("missing parameter" + (missing.size() > 1 ? "s " : " ") + String.join(", ", missing)
                    + " - widget '" + spec.id() + "' needs " + describe(spec));
        return new Bound(substitute(spec.custom().pipeline(), nodes), values);
    }

    /** Names referenced as {"$param": name} anywhere in a pipeline. */
    public static Set<String> referenced(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        collect(node, out);
        return out;
    }

    static JsonNode convert(WidgetSpec.Param p, String raw) {
        switch (p.type()) {
            case "date" -> {
                return F.objectNode().put("$date", isoDate(p.name(), raw));
            }
            case "number" -> {
                try {
                    return F.numberNode(new BigDecimal(raw));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("parameter " + p.name() + " must be a number, got '" + raw + "'");
                }
            }
            default -> {
                if (raw.length() > 200 || raw.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("parameter " + p.name() + " must be a short single-line string");
                return F.textNode(raw);
            }
        }
    }

    /** Accepts 2026-10-16 or a full ISO instant; returns an ISO instant in UTC. */
    static String isoDate(String name, String raw) {
        try {
            if (raw.length() == 10) return LocalDate.parse(raw).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toString();
            return Instant.parse(raw).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("parameter " + name + " must be a date (YYYY-MM-DD or ISO instant), got '" + raw + "'");
        }
    }

    private static JsonNode substitute(JsonNode node, Map<String, JsonNode> values) {
        if (node == null) return F.nullNode();
        if (isParamRef(node)) {
            String name = node.get(PARAM_KEY).asText();
            JsonNode v = values.get(name);
            if (v == null) throw new IllegalArgumentException("pipeline uses {\"$param\": \"" + name + "\"} but '" + name + "' is not declared in params");
            return v.deepCopy();
        }
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(n -> out.add(substitute(n, values)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), substitute(e.getValue(), values)));
            return out;
        }
        return node;
    }

    private static void collect(JsonNode node, Set<String> out) {
        if (node == null) return;
        if (isParamRef(node)) { out.add(node.get(PARAM_KEY).asText()); return; }
        if (node.isContainerNode()) node.forEach(n -> collect(n, out));
    }

    private static boolean isParamRef(JsonNode node) {
        return node.isObject() && node.size() == 1 && node.has(PARAM_KEY) && node.get(PARAM_KEY).isTextual();
    }

    private static String describe(WidgetSpec spec) {
        return String.join(", ", spec.paramsOrEmpty().stream().map(p -> p.name() + " (" + p.type() + ")").toList());
    }
}
