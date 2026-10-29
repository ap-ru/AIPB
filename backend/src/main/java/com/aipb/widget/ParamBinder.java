package com.aipb.widget;

import com.aipb.source.ReadOnlyCypher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds a custom spec's parameters for its Cypher query. A parameter is referenced as {@code $name} and passed to the
 * driver as a parameter value - never spliced into the text - so a value can't change the query's structure. Every
 * {@code $name} the query uses must be declared; values are type-checked. Dates are passed as ISO instants (text), so
 * the query compares with {@code datetime($asOfDate)}: the same on the driver and through the MCP server.
 */
public final class ParamBinder {

    public static final String AS_OF_DATE = "asOfDate";
    /** {@code $name} outside strings and comments ({@code $`name`} included). */
    private static final Pattern REF = Pattern.compile("\\$`?([A-Za-z_][A-Za-z0-9_]*)`?");

    private ParamBinder() {}

    /** The driver parameters, and the value each parameter resolved to (as given, after type checks). */
    public record Bound(Map<String, Object> params, Map<String, String> values) {}

    /**
     * Resolves every declared parameter from {@code given} (asOfDate falls back to {@code asOfDate}), type-checks it
     * and checks the query uses only declared ones. Throws IllegalArgumentException naming what is missing or malformed.
     */
    public static Bound bind(WidgetSpec spec, Map<String, String> given, Supplier<String> asOfDate) {
        Map<String, Object> params = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (WidgetSpec.Param p : spec.paramsOrEmpty()) {
            String raw = given == null ? null : given.get(p.name());
            if ((raw == null || raw.isBlank()) && AS_OF_DATE.equals(p.name())) raw = asOfDate.get();
            if (raw == null || raw.isBlank()) { missing.add(p.name()); continue; }
            params.put(p.name(), convert(p, raw.trim()));
            values.put(p.name(), raw.trim());
        }
        // asOfDate is built in: a query may use it without declaring it
        Set<String> used = referenced(spec.custom().cypher());
        if (used.contains(AS_OF_DATE) && !params.containsKey(AS_OF_DATE)) params.put(AS_OF_DATE, isoDate(AS_OF_DATE, asOfDate.get()));
        if (!missing.isEmpty())
            throw new IllegalArgumentException("missing parameter" + (missing.size() > 1 ? "s " : " ") + String.join(", ", missing)
                    + " - widget '" + spec.id() + "' needs " + describe(spec));
        for (String u : used)
            if (!params.containsKey(u))
                throw new IllegalArgumentException("the query uses $" + u + " but '" + u + "' is not declared in params");
        return new Bound(params, values);
    }

    /** Names referenced as {@code $name} in a Cypher query (not inside strings or comments). */
    public static Set<String> referenced(String cypher) {
        Set<String> out = new LinkedHashSet<>();
        if (cypher == null) return out;
        Matcher m = REF.matcher(ReadOnlyCypher.code(cypher));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static Object convert(WidgetSpec.Param p, String raw) {
        switch (p.type()) {
            case "date" -> {
                return isoDate(p.name(), raw);
            }
            case "number" -> {
                try {
                    BigDecimal d = new BigDecimal(raw);
                    return d.scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0 ? (Object) d.longValueExact() : (Object) d.doubleValue();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException("parameter " + p.name() + " must be a number, got '" + raw + "'");
                }
            }
            default -> {
                if (raw.length() > 200 || raw.chars().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("parameter " + p.name() + " must be a short single-line string");
                return raw;
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

    private static String describe(WidgetSpec spec) {
        return String.join(", ", spec.paramsOrEmpty().stream().map(p -> p.name() + " (" + p.type() + ")").toList());
    }
}
