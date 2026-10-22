package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

/**
 * The answer to a semantic query: columns whose type, unit, format and meaning are derived from the semantic model
 * (never declared by hand), the rows, and how they were produced. {@code truncated}: a source limit cut a query short,
 * so the figures are incomplete. {@code hasMore}: a records query had more rows than its limit (expected, not an error).
 * {@code queries} are the pipelines that ran - the audit trail behind every number.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResultSet(List<Column> columns, ArrayNode rows, int count, boolean truncated, boolean hasMore, String asOfDate,
                        List<Executed> queries, List<String> notes, Applied context) {

    /**
     * The context the answer was computed in, for the card to state: whose figures ({@code entityId}, or the book),
     * over which period (the window as it ran), and which page filters were applied - in words, e.g. "Region: EMEA".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Applied(String scope, String entityId, String period, List<String> filters) {}

    /**
     * One result column. {@code role}: dimension | display (a keyed dimension's label) | metric | prior (a metric over
     * the compare window) | field (a record field). {@code unitFrom}: the unit varies per row and is read from another
     * column. {@code hidden}: carried for another column (e.g. that unit), not for display.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Column(String name, String label, String type, String unit, String format, String grain, String role,
                         String polarity, Map<String, String> tones, UnitFrom unitFrom, Boolean hidden, Lineage lineage) {
        public String unitOrDefault() { return unit == null ? "none" : unit; }
        public String formatOrDefault() { return format != null ? format : defaultFormat(type, unit, tones != null); }
        public boolean isHidden() { return Boolean.TRUE.equals(hidden); }
        public Column withLabel(String l) { return new Column(name, l, type, unit, format, grain, role, polarity, tones, unitFrom, hidden, lineage); }
        public Column withTones(Map<String, String> t) { return new Column(name, label, type, unit, "badge", grain, role, polarity, t, unitFrom, hidden, lineage); }
    }

    public record UnitFrom(String column, Map<String, String> map) {}

    /**
     * Where a column comes from: the metric or field id and version, the models read, the definition in plain words
     * (shown to bankers), the data-dictionary fields it reads, and the resolved time window.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lineage(String kind, String id, Integer version, List<String> models, String definition, List<String> sources,
                          String window) {}

    /** One pipeline that ran: why, against which model and collection, and what came back. */
    public record Executed(String purpose, String model, String collection, JsonNode pipeline, int rows, boolean truncated) {}

    public Column column(String name) { return columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null); }

    /** The format a unit is shown in by default; the design system's formats (see widgets/primitives.json). */
    public static String defaultFormat(String type, String unit, boolean tones) {
        if (tones) return "badge";
        if (unit != null) switch (unit) {
            case "USD" -> { return "currency"; }
            case "ratio" -> { return "percent"; }
            case "percent" -> { return "percentPoints"; }
            case "count" -> { return "integer"; }
            case "date" -> { return "date"; }
            case "multiple" -> { return "multiple"; }
            case "days" -> { return "days"; }
            default -> { }
        }
        return switch (type) {
            case "number" -> "number";
            case "date" -> "date";
            case "boolean" -> "boolean";
            default -> "text";
        };
    }
}
