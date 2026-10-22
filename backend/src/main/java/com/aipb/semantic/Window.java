package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A time window, always relative to the data's today (asOfDate) - never the wall clock. This is the one place
 * date-window logic lives; specs and prompts name a window, they never do date arithmetic.
 * <ul>
 *   <li>{@code all} - no time filter</li>
 *   <li>{@code ytd} - calendar year to date</li>
 *   <li>{@code trailing} n units - the n units up to today (for closed-month data: up to the current month, which has no rows yet)</li>
 *   <li>{@code lastClosed} n units - the n whole calendar units before the current one (last 8 closed quarters)</li>
 *   <li>{@code current} unit - the calendar unit containing today (this quarter)</li>
 *   <li>{@code next} n units - from today forward (deals closing in the next 90 days)</li>
 *   <li>{@code range} - explicit [from, to) dates</li>
 * </ul>
 * {@code on} names which of a model's dates the window applies to (e.g. maturity); by default its time field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Window(String type, String unit, Integer n, String from, String to, String on) {

    public static final Set<String> TYPES = Set.of("all", "ytd", "trailing", "lastClosed", "current", "next", "range");
    public static final Set<String> UNITS = Set.of("day", "month", "quarter", "year");
    public static final Set<String> COMPARES = Set.of("priorYear", "priorPeriod");
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    public static Window of(String type) { return new Window(type, null, null, null, null, null); }

    public static Window of(String type, String unit, int n) { return new Window(type, unit, n, null, null, null); }

    /** A half-open date range [start, end), in UTC days. */
    public record Range(LocalDate start, LocalDate end) {
        public String startIso() { return start.atStartOfDay().toInstant(ZoneOffset.UTC).toString(); }
        public String endIso() { return end.atStartOfDay().toInstant(ZoneOffset.UTC).toString(); }
        public Range minus(Shift s) { return new Range(s.back(start), s.back(end)); }
        public boolean empty() { return !start.isBefore(end); }
        /** e.g. "Jan 1 - Sep 30, 2026" (the inclusive last day). */
        public String describe() {
            if (empty()) return "an empty period";
            return SHORT.format(start) + " - " + SHORT.format(end.minusDays(1));
        }
    }

    /** How far a comparison window lies behind its window: priorYear is 1 year, priorPeriod the window's own length. */
    public record Shift(String unit, int amount) {
        public LocalDate back(LocalDate d) { return add(d, unit, -amount); }
        public LocalDate forward(LocalDate d) { return add(d, unit, amount); }
        public String describe() {
            return unit.equals("year") && amount == 1 ? "a year earlier" : amount + " " + unit + (amount == 1 ? "" : "s") + " earlier";
        }
    }

    /** What is wrong with this window, as messages; empty when it is well formed. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (type == null || !TYPES.contains(type)) {
            out.add("window type must be one of " + String.join(", ", TYPES.stream().sorted().toList()) + (type == null ? "" : "; got '" + type + "'"));
            return out;
        }
        boolean needsUnit = Set.of("trailing", "lastClosed", "current", "next").contains(type);
        boolean needsN = Set.of("trailing", "lastClosed", "next").contains(type);
        if (needsUnit && (unit == null || !UNITS.contains(unit)))
            out.add("window " + type + " needs unit day, month, quarter or year" + (unit == null ? "" : "; got '" + unit + "'"));
        if (needsN && (n == null || n < 1 || n > 120)) out.add("window " + type + " needs n between 1 and 120");
        if (type.equals("range")) {
            LocalDate f = parse(from), t = parse(to);
            if (f == null || t == null) out.add("window range needs from and to as YYYY-MM-DD dates");
            else if (!f.isBefore(t)) out.add("window range: from must be before to");
        }
        return out;
    }

    /** The concrete range relative to {@code asOf}, or null for {@code all}. {@code closedOnly}: the data has rows only for closed months. */
    public Range resolve(LocalDate asOf, boolean closedOnly) {
        LocalDate anchor = closedOnly ? asOf.withDayOfMonth(1) : asOf.plusDays(1);
        return switch (type) {
            case "all" -> null;
            case "ytd" -> new Range(asOf.withDayOfYear(1), anchor);
            case "trailing" -> new Range(add(anchor, unit, -n), anchor);
            case "lastClosed" -> { LocalDate s = start(asOf, unit); yield new Range(add(s, unit, -n), s); }
            case "current" -> { LocalDate s = start(asOf, unit); yield new Range(s, add(s, unit, 1)); }
            case "next" -> new Range(asOf, add(asOf, unit, n));
            case "range" -> new Range(parse(from), parse(to));
            default -> throw new IllegalStateException("unchecked window type " + type);
        };
    }

    /** The shift for a comparison, or null when this window has no prior period (all time). */
    public Shift shift(String compare) {
        if ("priorYear".equals(compare)) return new Shift("year", 1);
        return switch (type) {
            case "ytd" -> new Shift("year", 1);
            case "trailing", "lastClosed", "next" -> new Shift(unit, n);
            case "current" -> new Shift(unit, 1);
            case "range" -> new Shift("day", (int) ChronoUnit.DAYS.between(parse(from), parse(to)));
            default -> null;
        };
    }

    /** Plain words, e.g. "last 8 closed quarters". */
    public String describe() {
        String on = this.on == null ? "" : " (by " + this.on + " date)";
        return switch (type) {
            case "all" -> "all time";
            case "ytd" -> "calendar year to date";
            case "trailing" -> "last " + n + " " + plural(unit, n);
            case "lastClosed" -> "last " + n + " closed " + plural(unit, n);
            case "current" -> "this " + unit;
            case "next" -> "next " + n + " " + plural(unit, n);
            case "range" -> "from " + from + " to " + to;
            default -> type;
        } + on;
    }

    /**
     * The window a {@link #key()} names, e.g. "trailing:month:12", "ytd", "current:quarter", "range:2026-01-01:2026-07-01",
     * "next:day:90@maturity". Throws IllegalArgumentException for a malformed key or window.
     */
    public static Window parseKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("empty window");
        String on = null;
        String k = key.trim();
        int at = k.indexOf('@');
        if (at >= 0) { on = k.substring(at + 1); k = k.substring(0, at); }
        String[] p = k.split(":");
        Window w;
        try {
            w = switch (p[0]) {
                case "range" -> new Window("range", null, null, p.length > 1 ? p[1] : null, p.length > 2 ? p[2] : null, on);
                default -> new Window(p[0], p.length > 1 ? p[1] : null, p.length > 2 ? Integer.valueOf(p[2]) : null, null, null, on);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("window '" + key + "': n must be a number");
        }
        if (w.type().equals("current") && w.n() == null) w = new Window("current", w.unit(), 1, null, null, on);
        List<String> problems = w.problems();
        if (!problems.isEmpty()) throw new IllegalArgumentException("window '" + key + "': " + String.join("; ", problems));
        return w;
    }

    /** The same window applied to another of a model's dates. */
    public Window onDate(String date) { return new Window(type, unit, n, from, to, date); }

    /** A canonical key: equal windows have equal keys. */
    @JsonIgnore
    public String key() {
        return type + (unit == null ? "" : ":" + unit) + (n == null ? "" : ":" + n) + (from == null ? "" : ":" + from + ":" + to)
                + (on == null ? "" : "@" + on);
    }

    // ------------------------------------------------------------------ calendar arithmetic
    static LocalDate start(LocalDate d, String unit) {
        return switch (unit) {
            case "day" -> d;
            case "month" -> d.withDayOfMonth(1);
            case "quarter" -> d.withDayOfMonth(1).withMonth(((d.getMonthValue() - 1) / 3) * 3 + 1);
            case "year" -> d.withDayOfYear(1);
            default -> throw new IllegalArgumentException("unit " + unit);
        };
    }

    static LocalDate add(LocalDate d, String unit, int amount) {
        return switch (unit) {
            case "day" -> d.plusDays(amount);
            case "month" -> d.plusMonths(amount);
            case "quarter" -> d.plusMonths(3L * amount);
            case "year" -> d.plusYears(amount);
            default -> throw new IllegalArgumentException("unit " + unit);
        };
    }

    private static String plural(String unit, int n) { return n == 1 ? unit : unit + "s"; }

    private static LocalDate parse(String s) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
