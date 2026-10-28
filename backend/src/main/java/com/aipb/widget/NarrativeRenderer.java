package com.aipb.widget;

import com.aipb.semantic.ResultSet;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders an insight: the {@code narrative} primitive's statements, filled from the first row. A pure function - no
 * model call when an insight is viewed. A statement shows when its condition holds (or it has none) and every slot it
 * names has a value; the first statement shown is the headline. Slots, inside a statement's text:
 * <ul>
 *   <li>{@code {column}} - the value, formatted as its column says (currency, percent, ...);</li>
 *   <li>{@code {column:format}} - with another format of the design system (e.g. {@code {revenueYoY:percent}});</li>
 *   <li>{@code {column>0?above:below}} - a word chosen by a comparison (ops {@code > >= < <= = !=}).</li>
 * </ul>
 * Formatting matches the frontend's (frontend/src/widgets/spec/format.js).
 */
public final class NarrativeRenderer {

    static final Pattern SLOT = Pattern.compile("\\{([^{}]*)}");
    private static final Pattern CHOICE = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(>=|<=|!=|>|<|=)\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*\\?([^:]*):(.*)$");
    private static final Pattern PLAIN = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?::\\s*([A-Za-z]+)\\s*)?$");
    static final Set<String> OPS = Set.of(">", ">=", "<", "<=", "=", "!=");
    static final Set<String> TONES = Set.of("good", "warn", "bad", "info", "neutral");
    static final int MAX_STATEMENTS = 8;

    private NarrativeRenderer() {}

    public record Line(String text, String tone) {}

    public record Figure(String name, String label, String text) {}

    /** What an insight says for a run: its lines (headline first) and figures; {@code skipped} statements lacked a value. */
    public record Narrative(List<Line> lines, List<Figure> figures, int skipped) {}

    /** A problem with a narrative's definition: where it is (a path under view) and what is wrong. */
    public record Problem(String path, String message) {}

    /** The checks that need no data: statements, conditions, slots and figures against the widget's columns. */
    public static List<Problem> problems(WidgetSpec.View v, Map<String, ResultSet.Column> cols, Set<String> formats) {
        List<Problem> out = new ArrayList<>();
        List<WidgetSpec.Statement> statements = v.statements() == null ? List.of() : v.statements();
        if (statements.isEmpty()) out.add(new Problem("view.statements", "a narrative needs at least one statement"));
        if (statements.size() > MAX_STATEMENTS)
            out.add(new Problem("view.statements", "a narrative has at most " + MAX_STATEMENTS + " statements; got " + statements.size()));
        for (int i = 0; i < statements.size(); i++) {
            WidgetSpec.Statement s = statements.get(i);
            String at = "view.statements[" + i + "]";
            if (s.text() == null || s.text().isBlank()) out.add(new Problem(at + ".text", "a statement needs text"));
            else slots(s.text()).forEach(slot -> slotProblem(slot, cols, formats).ifPresent(m -> out.add(new Problem(at + ".text", m))));
            if (s.tone() != null && !TONES.contains(s.tone()))
                out.add(new Problem(at + ".tone", "tone is one of " + String.join(", ", new TreeSet<>(TONES)) + "; got '" + s.tone() + "'"));
            WidgetSpec.When w = s.when();
            if (w != null) {
                ResultSet.Column c = w.column() == null ? null : cols.get(w.column());
                if (c == null) out.add(new Problem(at + ".when.column", "the condition names '" + w.column() + "', which is not a column; columns: "
                        + String.join(", ", cols.keySet())));
                else if (!"number".equals(c.type()) && !Set.of("=", "!=").contains(w.op()))
                    out.add(new Problem(at + ".when.op", "'" + w.column() + "' is " + c.type() + "; compare it with = or != only"));
                if (w.op() == null || !OPS.contains(w.op())) out.add(new Problem(at + ".when.op", "op is one of " + String.join(" ", new TreeSet<>(OPS))));
                if (w.value() == null || w.value().isNull()) out.add(new Problem(at + ".when.value", "the condition needs a value"));
                else if (c != null && "number".equals(c.type()) && !w.value().isNumber())
                    out.add(new Problem(at + ".when.value", "'" + w.column() + "' is a number; compare it with a number"));
            }
        }
        List<String> figures = v.figures() == null ? List.of() : v.figures();
        for (String f : figures)
            if (!cols.containsKey(f)) out.add(new Problem("view.figures", "figure '" + f + "' is not a column; columns: " + String.join(", ", cols.keySet())));
        return out;
    }

    /** The narrative for these rows; it reads the first row (no rows: nothing to say). */
    public static Narrative render(WidgetSpec.View v, List<ResultSet.Column> columns, JsonNode rows) {
        Map<String, ResultSet.Column> cols = new LinkedHashMap<>();
        columns.forEach(c -> cols.put(c.name(), c));
        JsonNode row = rows != null && rows.size() > 0 ? rows.get(0) : null;
        List<Line> lines = new ArrayList<>();
        int skipped = 0;
        if (row != null && v.statements() != null) {
            for (WidgetSpec.Statement s : v.statements()) {
                if (s.when() != null && !holds(s.when(), row)) continue;
                String text = fill(s.text(), row, cols);
                if (text == null) { skipped++; continue; }
                lines.add(new Line(text, s.tone() == null ? "neutral" : s.tone()));
            }
        }
        List<Figure> figures = new ArrayList<>();
        if (row != null && v.figures() != null) {
            for (String f : v.figures()) {
                ResultSet.Column c = cols.get(f);
                if (c == null) continue;
                figures.add(new Figure(f, c.label() == null ? f : c.label(), format(row.get(f), c, null)));
            }
        }
        return new Narrative(List.copyOf(lines), List.copyOf(figures), skipped);
    }

    static List<String> slots(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SLOT.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static Optional<String> slotProblem(String slot, Map<String, ResultSet.Column> cols, Set<String> formats) {
        Matcher choice = CHOICE.matcher(slot);
        if (choice.matches()) {
            ResultSet.Column c = cols.get(choice.group(1));
            if (c == null) return Optional.of("{" + slot + "} compares '" + choice.group(1) + "', which is not a column");
            if (!"number".equals(c.type())) return Optional.of("{" + slot + "} compares '" + choice.group(1) + "', which is not a number");
            return Optional.empty();
        }
        Matcher plain = PLAIN.matcher(slot);
        if (!plain.matches())
            return Optional.of("{" + slot + "} is not a slot: use {column}, {column:format} or {column>0?word:word}");
        if (!cols.containsKey(plain.group(1))) return Optional.of("{" + slot + "} names '" + plain.group(1) + "', which is not a column; columns: "
                + String.join(", ", cols.keySet()));
        if (plain.group(2) != null && !formats.contains(plain.group(2)))
            return Optional.of("{" + slot + "} asks for format '" + plain.group(2) + "'; formats: " + String.join(", ", new TreeSet<>(formats)));
        return Optional.empty();
    }

    /** The text with every slot filled, or null when a slot has no value in the row. */
    private static String fill(String text, JsonNode row, Map<String, ResultSet.Column> cols) {
        Matcher m = SLOT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String slot = m.group(1);
            String value;
            Matcher choice = CHOICE.matcher(slot);
            if (choice.matches()) {
                JsonNode x = row.get(choice.group(1));
                if (x == null || !x.isNumber()) return null;
                value = compare(x.asDouble(), choice.group(2), Double.parseDouble(choice.group(3))) ? choice.group(4).trim() : choice.group(5).trim();
            } else {
                Matcher plain = PLAIN.matcher(slot);
                if (!plain.matches()) return null;
                JsonNode x = row.get(plain.group(1));
                if (x == null || x.isNull()) return null;
                value = format(x, cols.get(plain.group(1)), plain.group(2));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean holds(WidgetSpec.When w, JsonNode row) {
        JsonNode x = row.get(w.column());
        if (x == null || x.isNull() || w.value() == null) return false;
        if (x.isNumber() && w.value().isNumber()) return compare(x.asDouble(), w.op(), w.value().asDouble());
        boolean eq = x.asText().equals(w.value().asText());
        return "=".equals(w.op()) ? eq : "!=".equals(w.op()) && !eq;
    }

    private static boolean compare(double a, String op, double b) {
        return switch (op) {
            case ">" -> a > b;
            case ">=" -> a >= b;
            case "<" -> a < b;
            case "<=" -> a <= b;
            case "=" -> a == b;
            case "!=" -> a != b;
            default -> false;
        };
    }

    /** A value as the frontend shows it (formatCell). */
    static String format(JsonNode v, ResultSet.Column c, String override) {
        if (v == null || v.isNull()) return "—";
        String f = override != null ? override : c == null ? "text" : c.formatOrDefault();
        if (v.isNumber()) {
            double d = v.asDouble();
            return switch (f) {
                case "currency" -> money(d);
                case "percent" -> fixed(Math.abs(d * 100), Math.abs(d) < 0.1 ? 1 : 0, d < 0) + "%";
                case "percentPoints" -> fixed(Math.abs(d), d == Math.rint(d) ? 0 : 1, d < 0) + "%";
                case "integer" -> String.format(Locale.US, "%,d", Math.round(d));
                case "multiple" -> String.format(Locale.US, "%,.2fx", d);
                case "days" -> { long n = Math.round(d); yield String.format(Locale.US, "%,d", n) + (Math.abs(n) == 1 ? " day" : " days"); }
                default -> number(d);
            };
        }
        if ("date".equals(f) || (c != null && "date".equals(c.type()))) {
            try {
                ZonedDateTime t = Instant.parse(v.asText()).atZone(ZoneOffset.UTC);
                String grain = c == null ? null : c.grain();
                if ("year".equals(grain)) return String.valueOf(t.getYear());
                if ("quarter".equals(grain)) return t.getYear() + " Q" + ((t.getMonthValue() - 1) / 3 + 1);
                if (grain != null) return t.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US));
                return t.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
            } catch (RuntimeException e) {
                return v.asText();
            }
        }
        if (v.isBoolean()) return v.asBoolean() ? "Yes" : "No";
        return v.asText();
    }

    private static String money(double v) {
        double a = Math.abs(v);
        String s = v < 0 ? "-" : "";
        if (a >= 1e9) return s + "$" + fixed(a / 1e9, 2, false) + "B";
        if (a >= 1e6) return s + "$" + fixed(a / 1e6, 1, false) + "M";
        if (a >= 1e3) return s + "$" + fixed(a / 1e3, 0, false) + "K";
        return s + "$" + fixed(a, 0, false);
    }

    private static String fixed(double v, int digits, boolean negative) {
        return (negative ? "-" : "") + String.format(Locale.US, "%." + digits + "f", v);
    }

    private static String number(double d) {
        String s = String.format(Locale.US, "%,.2f", d);
        return s.contains(".") ? s.replaceAll("\\.?0+$", "") : s;
    }
}
