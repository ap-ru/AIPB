package com.aipb.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Scores an assistant-made widget against the verified starter widget for the same request, on the numbers, not the
 * looks: the same rows for the same parameters must carry the same figures. Display choices (primitive, labels,
 * column names, ordering) are free.
 * <ul>
 *   <li>metric: the expected headline value appears in the actual (single) row;</li>
 *   <li>bar / line: same number of rows, and every expected series' values appear as some actual numeric column; with a
 *   series role (long-format rows, one per period and series), each series' values - one per period - must appear as
 *   some actual column (one row per period), or the actual rows may be long-format too;</li>
 *   <li>table / list: same number of rows, and every expected numeric column (or, without one, the first text column)
 *   appears as some actual column.</li>
 * </ul>
 * Values match within 0.5% (or 1 unit), compared as sorted lists so ordering choices don't matter.
 */
public final class EvalScorer {

    private EvalScorer() {}

    public record Score(boolean pass, String reason) {}

    public static Score score(JsonNode expectedView, JsonNode expectedRows, JsonNode actualRows) {
        String primitive = expectedView.path("primitive").asText();
        if (actualRows == null || !actualRows.isArray()) return new Score(false, "no rows");
        switch (primitive) {
            case "metric" -> {
                double want = expectedRows.path(0).path(expectedView.path("value").asText()).asDouble(Double.NaN);
                if (actualRows.size() != 1) return new Score(false, "expected one row, got " + actualRows.size());
                for (double v : numbers(actualRows.get(0))) if (close(v, want)) return new Score(true, "value " + want + " matches");
                return new Score(false, "value " + want + " not found in " + numbers(actualRows.get(0)));
            }
            case "bar", "line" -> {
                if (expectedView.hasNonNull("series")) return scoreSeries(expectedView, expectedRows, actualRows);
                if (actualRows.size() != expectedRows.size()) return new Score(false, "expected " + expectedRows.size() + " rows, got " + actualRows.size());
                for (JsonNode y : expectedView.path("y")) {
                    List<Double> want = column(expectedRows, y.asText());
                    if (!anyColumnMatches(actualRows, want)) return new Score(false, "series " + y.asText() + " " + want + " not found");
                }
                return new Score(true, "all series match");
            }
            default -> {
                if (actualRows.size() != expectedRows.size()) return new Score(false, "expected " + expectedRows.size() + " rows, got " + actualRows.size());
                List<String> numeric = numericColumns(expectedRows);
                for (String c : numeric) {
                    if (!anyColumnMatches(actualRows, column(expectedRows, c))) return new Score(false, "column " + c + " not found");
                }
                if (numeric.isEmpty() && expectedRows.size() > 0) {
                    String text = expectedRows.get(0).fieldNames().next();
                    List<String> want = texts(expectedRows, text);
                    boolean found = false;
                    for (Iterator<String> it = actualRows.get(0).fieldNames(); it.hasNext(); ) if (texts(actualRows, it.next()).equals(want)) found = true;
                    if (!found) return new Score(false, "column " + text + " not found");
                }
                return new Score(true, actualRows.size() + " rows match");
            }
        }
    }

    /** Expected long-format rows (x, series, one y): each series' values per x must be some actual column, or match long-format. */
    private static Score scoreSeries(JsonNode view, JsonNode expectedRows, JsonNode actualRows) {
        String x = view.path("x").asText(), series = view.path("series").asText(), y = view.path("y").path(0).asText();
        Map<String, Map<String, Double>> bySeries = new TreeMap<>();
        Set<String> periods = new TreeSet<>();
        for (JsonNode r : expectedRows) {
            periods.add(r.path(x).asText());
            bySeries.computeIfAbsent(r.path(series).asText(), k -> new TreeMap<>()).put(r.path(x).asText(), r.path(y).asDouble());
        }
        if (actualRows.size() == expectedRows.size() && anyColumnMatches(actualRows, column(expectedRows, y)))
            return new Score(true, "all " + bySeries.size() + " series match (long format)");
        if (actualRows.size() != periods.size()) return new Score(false, "expected " + periods.size() + " periods, got " + actualRows.size() + " rows");
        for (Map.Entry<String, Map<String, Double>> e : bySeries.entrySet()) {
            List<Double> want = new ArrayList<>();
            periods.forEach(p -> want.add(e.getValue().getOrDefault(p, 0.0)));
            if (!anyColumnMatches(actualRows, want)) return new Score(false, "series " + e.getKey() + " not found");
        }
        return new Score(true, "all " + bySeries.size() + " series match");
    }

    // ------------------------------------------------------------------ helpers
    private static boolean anyColumnMatches(JsonNode rows, List<Double> want) {
        List<Double> sortedWant = new ArrayList<>(want);
        Collections.sort(sortedWant);
        for (String c : numericColumns(rows)) {
            List<Double> got = column(rows, c);
            Collections.sort(got);
            if (got.size() == sortedWant.size()) {
                boolean all = true;
                for (int i = 0; i < got.size() && all; i++) all = close(got.get(i), sortedWant.get(i));
                if (all) return true;
            }
        }
        return false;
    }

    private static List<String> numericColumns(JsonNode rows) {
        List<String> out = new ArrayList<>();
        if (rows.size() == 0) return out;
        rows.get(0).fields().forEachRemaining(e -> { if (e.getValue().isNumber()) out.add(e.getKey()); });
        return out;
    }

    private static List<Double> column(JsonNode rows, String name) {
        List<Double> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.path(name).isNumber() ? r.get(name).asDouble() : Double.NaN));
        return out;
    }

    private static List<String> texts(JsonNode rows, String name) {
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.path(name).asText()));
        Collections.sort(out);
        return out;
    }

    private static List<Double> numbers(JsonNode row) {
        List<Double> out = new ArrayList<>();
        row.fields().forEachRemaining(e -> { if (e.getValue().isNumber()) out.add(e.getValue().asDouble()); });
        return out;
    }

    static boolean close(double a, double b) {
        if (Double.isNaN(a) || Double.isNaN(b)) return false;
        return Math.abs(a - b) <= Math.max(1.0, Math.abs(b) * 0.005);
    }
}
