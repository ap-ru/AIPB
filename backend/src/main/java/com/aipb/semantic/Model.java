package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The physical mapping of one node label of the bank graph: which conformed dimensions it binds to which properties,
 * its time property, its named segments, its measures (aggregations of a property or a Cypher expression over the
 * node {@code n}) and the record fields it can list. A path like {@code client.region} goes through a join named
 * {@code client} - a relationship to another label; joins are always many-to-one (each node has at most one at the
 * other end), so they never multiply rows. Models are developer-authored; the rest of the app only refers to their ids.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Model(String id, String label, int version, String description, String node, String entity,
                    boolean entityModel, Join entityJoin, TimeSpec time, Map<String, String> dates, Map<String, Join> joins,
                    Map<String, Binding> dimensions, Map<String, Segment> segments, List<Measure> measures, Records records) {

    /** The default date: windows filter on it, and month/quarter/year group by it. {@code closedOnly}: rows exist only for closed months. */
    public record TimeSpec(String field, String grain, boolean closedOnly) {}

    /**
     * A many-to-one path from this model's node to a node labelled {@code node}: one or more hops, each a relationship
     * {@code rel} ({@code dir} out: {@code (a)-[:rel]->(b)}, in: {@code (a)<-[:rel]-(b)}) that is many-to-one from its
     * start, so the whole path is too. The entity join's {@code node} is the entity model's.
     */
    public record Join(List<Hop> hops) {
        public static Join of(String rel, String dir, String node) { return new Join(List.of(new Hop(rel, dir, node))); }

        /** The label at the end of the path. */
        public String node() { return hops.isEmpty() ? null : hops.get(hops.size() - 1).node(); }

        public String rel() { return hops.isEmpty() ? null : hops.get(0).rel(); }

        public String dir() { return hops.isEmpty() ? null : hops.get(0).dir(); }
    }

    /** One relationship of a join's path. */
    public record Hop(String rel, String dir, String node) {}

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
