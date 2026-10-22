package com.aipb.widget;

import com.aipb.semantic.SemanticQuery;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A declarative widget, as defined by resources/widgets/widget-spec.schema.json (format 2): what data it shows and how
 * one design-system primitive shows it ({@link View}). The data is either
 * <ul>
 *   <li>a semantic {@code query} - metrics, dimensions, a window - whose columns (type, unit, format, meaning) are
 *       derived from the semantic layer, with optional label / tone {@code columns} overrides; or</li>
 *   <li>{@code custom}: the reviewed escape hatch, a hand-written read-only pipeline over one collection and the
 *       columns it declares, with {@code params} it references as {"$param": name}.</li>
 * </ul>
 * Always parse through {@link SpecMigrator#read}, which also accepts format 1 (data = collection + pipeline + columns).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record WidgetSpec(String id, Integer specVersion, Integer version, String title, String description, List<Param> params,
                         Data data, View view, LayoutHint layout, JsonNode provenance) {

    public List<Param> paramsOrEmpty() { return params == null ? List.of() : params; }

    public Param param(String name) {
        return paramsOrEmpty().stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    /** True when the data is a semantic query, false for a custom pipeline. */
    public boolean isQuery() { return data != null && data.query() != null; }

    public Custom custom() { return data == null ? null : data.custom(); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Param(String name, String type, String description) {}

    /** {@code query} + optional {@code columns} overrides, or {@code custom}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(SemanticQuery query, List<Column> columns, Custom custom) {
        public List<Column> overridesOrEmpty() { return columns == null ? List.of() : columns; }
    }

    /** A hand-written pipeline over one source collection, and the columns every result row has. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Custom(String collection, JsonNode pipeline, List<Column> columns) {
        public Column column(String name) {
            return columns == null ? null : columns.stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** A declared column (custom data), or an override of a derived one (query data: name plus label and/or tones). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Column(String name, String type, String unit, String format, String label, Map<String, String> tones) {
        public String unitOrDefault() { return unit == null ? "none" : unit; }

        public String formatOrDefault() {
            if (format != null) return format;
            return switch (type) { case "number" -> "number"; case "date" -> "date"; default -> "text"; };
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record View(String primitive, String value, String compare, String secondary, String x, List<String> y,
                       String orientation, Boolean stacked, List<String> columns, String title, String subtitle,
                       String badge, String meta, String reference, String emphasis, String polarity, String headline,
                       Double warnAt, String series) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LayoutHint(Integer span) {}
}
