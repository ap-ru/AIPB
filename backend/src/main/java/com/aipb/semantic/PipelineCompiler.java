package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Compiles a planned source query into a read-only MongoDB aggregation pipeline. The only code that writes pipelines
 * for semantic queries, so every widget computes a metric the same way. Shape:
 * <pre>
 * $match  (entity scope, filters and segments on the collection's own fields, the date range)
 * $lookup + $set $first, per join a path needs (many-to-one: never multiplies rows)
 * $match  (conditions through a join)
 * aggregate: $group by the dimensions with one accumulator per measure (segment-conditional), then $project
 * records:   $project the fields, $sort, $limit
 * </pre>
 * Output is deterministic for a given plan (it is snapshot-tested), and the range is literal dates, resolved from the
 * data's today by the planner - so a compiled pipeline is also a precise record of what was asked.
 */
public final class PipelineCompiler {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    static final String JOIN_PREFIX = "_j_";
    static final String DISPLAY_SUFFIX = "__display";

    private PipelineCompiler() {}

    public static ArrayNode compile(QueryPlanner.PhysicalQuery q) {
        Model m = q.model();
        ArrayNode stages = F.arrayNode();

        // what goes through joins
        Set<String> joins = new LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        q.dims().forEach(d -> { paths.add(d.path()); if (d.displayPath() != null) paths.add(d.displayPath()); });
        if (q.fields() != null) q.fields().forEach(f -> { if (f.field() != null) paths.add(f.field()); });
        q.conditions().forEach(c -> paths.add(c.field()));
        q.aggs().forEach(a -> { if (a.measure().field() != null) paths.add(a.measure().field()); });
        for (String p : paths) { String j = joinOf(m, p); if (j != null) joins.add(j); }

        ObjectNode pre = F.objectNode();
        List<Condition> post = new ArrayList<>();
        for (Condition c : q.conditions()) {
            if (joinOf(m, c.field()) != null) post.add(c);
            else merge(pre, c.toMatch(c.field()));
        }
        if (q.range() != null) {
            ObjectNode r = F.objectNode();
            r.putObject("$gte").put("$date", q.range().startIso());
            r.putObject("$lt").put("$date", q.range().endIso());
            merge(pre, F.objectNode().set(q.rangeField(), r));
        }
        if (!pre.isEmpty()) stages.addObject().set("$match", pre);

        for (String name : joins) {
            Model.Join j = m.joins().get(name);
            ObjectNode lookup = stages.addObject().putObject("$lookup");
            lookup.put("from", j.collection()).put("localField", j.localField()).put("foreignField", j.foreignField()).put("as", JOIN_PREFIX + name);
            stages.addObject().putObject("$set").putObject(JOIN_PREFIX + name).put("$first", "$" + JOIN_PREFIX + name);
        }
        if (!post.isEmpty()) {
            ObjectNode match = F.objectNode();
            post.forEach(c -> merge(match, c.toMatch(path(m, c.field()))));
            stages.addObject().set("$match", match);
        }

        if (q.fields() != null) records(q, stages);
        else aggregate(q, stages);
        return stages;
    }

    private static void aggregate(QueryPlanner.PhysicalQuery q, ArrayNode stages) {
        Model m = q.model();
        ObjectNode group = stages.addObject().putObject("$group");
        if (q.dims().isEmpty()) group.putNull("_id");
        else {
            ObjectNode id = group.putObject("_id");
            for (QueryPlanner.DimRef d : q.dims()) {
                if (d.grain() != null) id.putObject(d.id()).putObject("$dateTrunc").put("date", "$" + d.path()).put("unit", d.grain());
                else id.put(d.id(), "$" + path(m, d.path()));
                if (d.displayPath() != null) id.put(d.id() + DISPLAY_SUFFIX, "$" + path(m, d.displayPath()));
            }
        }
        for (QueryPlanner.Agg a : q.aggs()) group.set(a.alias(), accumulator(m, a));
        ObjectNode project = stages.addObject().putObject("$project");
        project.put("_id", 0);
        for (QueryPlanner.DimRef d : q.dims()) {
            project.put(d.id(), "$_id." + d.id());
            if (d.displayPath() != null) project.put(d.displayColumn(), "$_id." + d.id() + DISPLAY_SUFFIX);
        }
        for (QueryPlanner.Agg a : q.aggs()) {
            if (a.measure().agg().equals("countDistinct")) {
                ArrayNode diff = project.putObject(a.alias()).putObject("$size").putArray("$setDifference");
                diff.add("$" + a.alias()).addArray().addNull();
            } else project.put(a.alias(), 1);
        }
    }

    /** One measure's accumulator; a measure's own segments make it conditional ($cond), so several share one pass. */
    private static JsonNode accumulator(Model m, QueryPlanner.Agg a) {
        Model.Measure ms = a.measure();
        JsonNode value = ms.agg().equals("count") ? F.numberNode(1)
                : ms.expr() != null ? ms.expr().deepCopy() : F.textNode("$" + path(m, ms.field()));
        JsonNode when = null;
        if (!a.segments().isEmpty()) {
            List<JsonNode> exprs = new ArrayList<>();
            a.segments().forEach(s -> m.segments().get(s).where().forEach(c -> exprs.add(c.toExpr(path(m, c.field())))));
            when = Condition.and(exprs);
        }
        boolean additive = ms.agg().equals("sum") || ms.agg().equals("count");
        if (when != null) {
            ArrayNode cond = F.arrayNode().add(when).add(value);
            if (additive) cond.add(0); else cond.addNull();
            value = F.objectNode().set("$cond", cond);
        }
        String op = switch (ms.agg()) {
            case "sum", "count" -> "$sum";
            case "avg" -> "$avg";
            case "min" -> "$min";
            case "max" -> "$max";
            case "countDistinct" -> "$addToSet";
            default -> throw new IllegalStateException("unchecked agg " + ms.agg());
        };
        return F.objectNode().set(op, value);
    }

    private static void records(QueryPlanner.PhysicalQuery q, ArrayNode stages) {
        Model m = q.model();
        ObjectNode project = stages.addObject().putObject("$project");
        project.put("_id", 0);
        for (Model.RecordField f : q.fields()) {
            if (f.expr() != null) project.set(f.id(), f.expr().deepCopy());
            else project.put(f.id(), "$" + path(m, f.field()));
        }
        if (!q.sort().isEmpty()) {
            ObjectNode sort = stages.addObject().putObject("$sort");
            q.sort().forEach(s -> sort.put(s.by(), s.descending() ? -1 : 1));
            if (m.records().key() != null && !sort.has(m.records().key())) {
                // a stable tie-break, so equal sort keys always come back in the same order
                project.put("_key", "$" + m.records().key());
                sort.put("_key", 1);
            }
        }
        if (q.limit() != null) stages.addObject().put("$limit", q.limit());
        if (project.has("_key")) stages.addObject().put("$unset", "_key");
    }

    /** The pipeline path of a model path: a path through a join reads the joined document. */
    static String path(Model m, String p) {
        String j = joinOf(m, p);
        return j == null ? p : JOIN_PREFIX + p;
    }

    private static String joinOf(Model m, String p) {
        int dot = p.indexOf('.');
        return dot > 0 && m.joins().containsKey(p.substring(0, dot)) ? p.substring(0, dot) : null;
    }

    /** Adds a {path: {...}} fragment to a $match, combining operators on the same path. */
    private static void merge(ObjectNode match, ObjectNode fragment) {
        fragment.fields().forEachRemaining(e -> {
            JsonNode existing = match.get(e.getKey());
            if (existing instanceof ObjectNode o && e.getValue() instanceof ObjectNode n && Collections.disjoint(fieldNames(o), fieldNames(n)))
                o.setAll(n);
            else if (existing != null) {
                ArrayNode and = match.has("$and") ? (ArrayNode) match.get("$and") : match.putArray("$and");
                and.addObject().set(e.getKey(), e.getValue());
            } else match.set(e.getKey(), e.getValue());
        });
    }

    private static Set<String> fieldNames(ObjectNode o) {
        Set<String> s = new HashSet<>();
        o.fieldNames().forEachRemaining(s::add);
        return s;
    }
}
