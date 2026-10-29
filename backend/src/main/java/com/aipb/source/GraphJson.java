package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Entity;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.TypeSystem;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns Neo4j values into the plain JSON a widget renders, the same from either reader: temporal values become ISO
 * instants with milliseconds ({@code 2026-10-27T00:00:00.000Z}, as the MongoDB readers wrote them), integers within
 * int range become ints, nodes and relationships become their property maps, and a property the dictionary stores as
 * JSON (nested records: Neo4j properties cannot nest) is parsed back into the records it holds. The MCP server writes
 * datetimes as text ({@code 2018-08-25T00:00:00.000000000+00:00}); {@link #simplify} normalizes those.
 */
public final class GraphJson {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern DATETIME_TEXT = Pattern.compile("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?(Z|[+-]\\d\\d:\\d\\d)");

    private GraphJson() {}

    /** One driver value as JSON; {@code json} names the properties stored as JSON text. */
    public static JsonNode of(Value v, Set<String> json) {
        if (v == null || v.isNull()) return F.nullNode();
        TypeSystem t = TypeSystem.getDefault();
        if (v.hasType(t.NODE()) || v.hasType(t.RELATIONSHIP())) return entity(v.asEntity(), json);
        if (v.hasType(t.PATH())) {
            ArrayNode out = F.arrayNode();
            for (Path.Segment s : v.asPath()) { if (out.isEmpty()) out.add(entity(s.start(), json)); out.add(entity(s.relationship(), json)); out.add(entity(s.end(), json)); }
            return out;
        }
        if (v.hasType(t.LIST())) {
            ArrayNode out = F.arrayNode();
            v.values().forEach(x -> out.add(of(x, json)));
            return out;
        }
        if (v.hasType(t.MAP())) {
            ObjectNode out = F.objectNode();
            for (String k : v.keys()) out.set(k, property(k, v.get(k), json));
            return out;
        }
        if (v.hasType(t.BOOLEAN())) return F.booleanNode(v.asBoolean());
        if (v.hasType(t.INTEGER())) { long n = v.asLong(); return n == (int) n ? F.numberNode((int) n) : F.numberNode(n); }
        if (v.hasType(t.FLOAT())) {
            double d = v.asDouble();
            return Double.isFinite(d) ? F.numberNode(d) : F.textNode(Double.isNaN(d) ? "NaN" : d > 0 ? "Infinity" : "-Infinity");
        }
        if (v.hasType(t.STRING())) return F.textNode(v.asString());
        if (v.hasType(t.DATE_TIME())) return F.textNode(INSTANT.format(v.asZonedDateTime().toInstant()));
        if (v.hasType(t.LOCAL_DATE_TIME())) return F.textNode(INSTANT.format(v.asLocalDateTime().toInstant(ZoneOffset.UTC)));
        if (v.hasType(t.DATE())) return F.textNode(INSTANT.format(v.asLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)));
        return F.textNode(v.toString());
    }

    /** A named value: a JSON-stored property is parsed back into its records. */
    public static JsonNode property(String name, Value v, Set<String> json) {
        if (json.contains(name) && v != null && v.hasType(TypeSystem.getDefault().STRING()))
            return parse(v.asString());
        return of(v, json);
    }

    private static JsonNode entity(Entity e, Set<String> json) {
        ObjectNode out = F.objectNode();
        for (String k : e.keys()) out.set(k, property(k, e.get(k), json));
        return out;
    }

    /**
     * JSON as the MCP server returns it, normalized to what {@link #of} produces: datetime text to an ISO instant,
     * JSON-stored properties parsed, everything else as is.
     */
    public static JsonNode simplify(JsonNode node, Set<String> json) {
        if (node == null) return F.nullNode();
        if (node.isArray()) {
            ArrayNode out = F.arrayNode();
            node.forEach(n -> out.add(simplify(n, json)));
            return out;
        }
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                out.set(e.getKey(), json.contains(e.getKey()) && v.isTextual() ? parse(v.asText()) : simplify(v, json));
            }
            return out;
        }
        if (node.isTextual() && DATETIME_TEXT.matcher(node.asText()).matches()) {
            try { return F.textNode(INSTANT.format(OffsetDateTime.parse(node.asText()).toInstant())); } catch (DateTimeParseException ignored) { }
        }
        return node;
    }

    private static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            return F.textNode(text);
        }
    }

    /** A driver parameter from JSON: numbers, text, booleans, lists and maps as themselves. */
    public static Object param(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isIntegralNumber()) return n.asLong();
        if (n.isNumber()) return n.asDouble();
        if (n.isBoolean()) return n.asBoolean();
        if (n.isTextual()) return n.asText();
        return MAPPER.convertValue(n, Object.class);
    }

    /** An ISO instant or date as a driver datetime parameter (UTC). */
    public static ZonedDateTime datetime(String iso) {
        try {
            return Instant.parse(iso).atZone(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC);
        }
    }
}
