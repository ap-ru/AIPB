package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The physical mapping of one source collection: which conformed dimensions it binds to which fields, its time field,
 * its named segments, its measures (aggregations of a field or expression) and the record fields it can list. A path
 * like {@code client.region} goes through a join named {@code client}; joins are always many-to-one (onto a primary
 * key), so they never multiply rows. Models are developer-authored; the rest of the app only refers to their ids.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Model(String id, String label, int version, String description, String collection, String entity,
                    boolean entityModel, TimeSpec time, Map<String, String> dates, Map<String, Join> joins,
                    Map<String, Binding> dimensions, Map<String, Segment> segments, List<Measure> measures, Records records) {

    /** The default date: windows filter on it, and month/quarter/year group by it. {@code closedOnly}: rows exist only for closed months. */
    public record TimeSpec(String field, String grain, boolean closedOnly) {}

    /** A many-to-one lookup: {@code localField} of this collection onto the key {@code foreignField} of {@code collection}. */
    public record Join(String collection, String localField, String foreignField) {}

    /** Where a dimension lives in this model: its value path, and for keyed dimensions the path of its label. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Binding(String field, String display) {}

    public record Segment(String description, List<Condition> where) {}

    /** An aggregation: sum | count | avg | min | max | countDistinct of a field or expression, over optional segments. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Measure(String id, String label, String agg, String field, JsonNode expr, List<String> sources,
                          List<String> segments, String unit, String polarity, String description, int version) {
        /** Sums and counts add up across rows and periods; a missing one is 0. Averages, minima and maxima do not. */
        public boolean additive() { return agg.equals("sum") || agg.equals("count") || agg.equals("countDistinct"); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Records(String key, List<RecordField> fields, List<SortKey> defaultSort) {
        public RecordField field(String id) { return fields.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null); }
    }

    /** A listable field: a path or expression, its type and unit, and optionally the dimension giving its values and tones. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordField(String id, String label, String field, JsonNode expr, List<String> sources, String type,
                              String unit, String dimension, UnitFrom unitFrom, String description) {}

    /** A per-row unit: the unit comes from another field's value, mapped to a design-system unit (x -> multiple). */
    public record UnitFrom(String field, Map<String, String> map) {}

    public record SortKey(String by, String dir) {
        public boolean descending() { return "desc".equals(dir); }
    }

    public Measure measure(String id) { return measures.stream().filter(m -> m.id().equals(id)).findFirst().orElse(null); }

    public boolean snapshot() { return time == null; }

    /** The field a window filters on: a named date, or the time field. Null when the model has no such date. */
    public String dateField(String on) {
        if (on == null) return time == null ? null : time.field();
        return dates.get(on);
    }
}
