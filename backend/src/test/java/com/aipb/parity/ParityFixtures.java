package com.aipb.parity;

import com.aipb.semantic.Model;
import com.aipb.semantic.SemanticModel;
import com.aipb.semantic.SemanticQuery;
import com.aipb.semantic.Window;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * What the graph has to reproduce, captured from the MongoDB stack before the move (graph-parity/ in test resources):
 * every starter widget for every client and the book, plus a battery of semantic queries that exercises each model's
 * measures by each of its dimensions, the time grains, the client join, derived metrics and record lists. Both sides
 * compare rows the same way ({@link #rowsMatch}), so a fixture written on MongoDB and a result read from Neo4j can be
 * checked against each other.
 */
public final class ParityFixtures {

    public static final String DIR = "graph-parity/";
    public static final List<String> CLIENTS_FOR_QUERIES = List.of("CL-0001", "CL-0007", "CL-0023", "CL-0042");

    private ParityFixtures() {}

    /** One battery query: a name, the query, and whether it runs for the book or for each of {@link #CLIENTS_FOR_QUERIES}. */
    public record Battery(String name, SemanticQuery query, boolean perClient) {}

    public static List<Battery> battery(SemanticModel sm) {
        List<Battery> out = new ArrayList<>();
        Window all = Window.of("all");
        for (Model m : sm.models().values()) {
            List<String> measures = m.measures().stream().map(Model.Measure::id).toList();
            out.add(new Battery(m.id() + " totals", agg(measures, List.of(), m.time() == null ? null : all), false));
            out.add(new Battery(m.id() + " totals per client", agg(measures, List.of(), m.time() == null ? null : all), true));
            for (String d : m.dimensions().keySet())
                out.add(new Battery(m.id() + " by " + d, agg(measures, List.of(d), m.time() == null ? null : all), false));
            if (!m.entityModel()) out.add(new Battery(m.id() + " by region", agg(measures, List.of("region"), m.time() == null ? null : all), false));
            if (m.time() != null) {
                out.add(new Battery(m.id() + " by month, last 12 closed", agg(measures, List.of("month"), Window.of("lastClosed", "month", 12)), false));
                out.add(new Battery(m.id() + " by quarter, trailing 4", agg(measures, List.of("quarter"), Window.of("trailing", "quarter", 4)), true));
                out.add(new Battery(m.id() + " by year", agg(measures, List.of("year"), all), false));
                out.add(new Battery(m.id() + " ytd vs prior year",
                        new SemanticQuery("aggregate", measures, List.of(), null, null, null, null, Window.of("ytd"), "priorYear", null, null, null, null, null), true));
            }
            if (m.records() != null) {
                List<String> fields = m.records().fields().stream().map(Model.RecordField::id).toList();
                out.add(new Battery(m.id() + " records", new SemanticQuery("records", null, null, m.id(), fields, null, null, null, null, null,
                        SemanticQuery.MAX_LIMIT, null, null, null), true));
            }
        }
        out.add(new Battery("derived by region", agg(new ArrayList<>(sm.derived().keySet()), List.of("region"), Window.of("trailing", "month", 12)), false));
        out.add(new Battery("derived per client", agg(new ArrayList<>(sm.derived().keySet()), List.of(), Window.of("trailing", "month", 12)), true));
        out.add(new Battery("revenue by lob and quarter, filtered", new SemanticQuery("aggregate", List.of("revenueUsd", "feeUsd"), List.of("lob", "quarter"), null,
                null, List.of(new SemanticQuery.Filter("region", "in", new ObjectMapper().valueToTree(List.of("EMEA", "APAC")))), null,
                Window.of("lastClosed", "quarter", 4), "priorPeriod", null, null, null, null, null), false));
        return out;
    }

    private static SemanticQuery agg(List<String> metrics, List<String> dims, Window w) {
        return new SemanticQuery("aggregate", metrics, dims, null, null, null, null, w, null, null, null, null, null, null);
    }

    /**
     * Rows match: the same number, and the same multiset of rows, where numbers agree within 1e-6 (or 1e-12 relative for large figures,
     * absolute near zero) and dates by instant. Order matters when {@code ordered} (a sorted records list).
     */
    public static Optional<String> rowsMatch(JsonNode want, JsonNode got, boolean ordered) {
        if (want.size() != got.size()) return Optional.of("row count " + got.size() + ", expected " + want.size());
        List<JsonNode> w = new ArrayList<>(), g = new ArrayList<>();
        want.forEach(w::add);
        got.forEach(g::add);
        if (!ordered) {
            Comparator<JsonNode> byKey = Comparator.comparing(ParityFixtures::key);
            w.sort(byKey);
            g.sort(byKey);
        }
        for (int i = 0; i < w.size(); i++) {
            Optional<String> diff = rowMatch(w.get(i), g.get(i));
            if (diff.isPresent()) return Optional.of("row " + i + ": " + diff.get() + "\n  expected " + w.get(i) + "\n  got      " + g.get(i));
        }
        return Optional.empty();
    }

    private static Optional<String> rowMatch(JsonNode want, JsonNode got) {
        Set<String> names = new TreeSet<>();
        want.fieldNames().forEachRemaining(names::add);
        got.fieldNames().forEachRemaining(names::add);
        for (String n : names) {
            JsonNode a = want.get(n), b = got.get(n);
            boolean an = a == null || a.isNull(), bn = b == null || b.isNull();
            if (an || bn) { if (an != bn) return Optional.of(n + ": " + b + ", expected " + a); continue; }
            if (a.isNumber() && b.isNumber()) {
                double x = a.asDouble(), y = b.asDouble();
                if (Math.abs(x - y) > Math.max(1e-6, Math.abs(x) * 1e-12)) return Optional.of(n + ": " + y + ", expected " + x);
            } else if (!text(a).equals(text(b))) return Optional.of(n + ": " + b + ", expected " + a);
        }
        return Optional.empty();
    }

    /** A row's comparable key: its non-numeric values, then its numbers rounded, so equal rows sort together. */
    private static String key(JsonNode row) {
        StringJoiner j = new StringJoiner("|");
        new TreeMap<String, JsonNode>(toMap(row)).forEach((k, v) -> j.add(k + "=" + (v == null || v.isNull() ? "" : v.isNumber()
                ? String.format(Locale.US, "%.3e", v.asDouble()) : text(v))));
        return j.toString();
    }

    private static Map<String, JsonNode> toMap(JsonNode row) {
        Map<String, JsonNode> m = new HashMap<>();
        row.fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue()));
        return m;
    }

    /** Text, with ISO instants normalized (MongoDB writes milliseconds, which an instant's toString drops when zero). */
    private static String text(JsonNode v) {
        String s = v.isTextual() ? v.asText() : v.toString();
        if (s.matches("\\d{4}-\\d\\d-\\d\\dT.*Z")) {
            try { return Instant.parse(s).toString(); } catch (RuntimeException ignored) { }
        }
        return s;
    }

    public static LocalDate day(String iso) { return LocalDate.ofInstant(Instant.parse(iso), ZoneOffset.UTC); }
}
