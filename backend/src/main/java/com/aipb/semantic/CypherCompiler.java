package com.aipb.semantic;

import com.aipb.source.GraphJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Compiles a planned source query into one read-only, parameterized Cypher query. The only code that writes Cypher for
 * semantic queries, so every widget computes a metric the same way. Shape:
 * <pre>
 * MATCH (n:Label) WHERE  (entity scope, filters and segments on the node's own properties, the date range)
 * OPTIONAL MATCH (n)-[:REL]-(join:Label), per join a path needs (many-to-one: never multiplies rows)
 * WITH n, join... WHERE  (conditions through a join)
 * aggregate: RETURN the dimensions (implicit grouping keys) and one aggregation per measure (CASE WHEN for its segments)
 * records:   RETURN the fields, ORDER BY (nulls first ascending, as MongoDB sorted), LIMIT
 * </pre>
 * Every value is a parameter ({@code $p0}, {@code $start}, ...) and every identifier comes from the validated model,
 * so nothing a widget or a page supplies is ever spliced into the text. Output is deterministic for a given plan (it is
 * snapshot-tested), and the range is literal dates, resolved from the data's today by the planner - so a compiled
 * query is also a precise record of what was asked. Dates travel as ISO text and are compared with {@code datetime()}.
 */
public final class CypherCompiler {

    static final String DISPLAY_SUFFIX = "__display";
    static final String NODE = "n";
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CypherCompiler() {}

    /** A compiled query: the Cypher text and its parameters (JSON values; dates as ISO text). */
    public record CompiledQuery(String cypher, Map<String, Object> params) {
        /** As JSON, for the lineage a result carries: {cypher, params}. */
        public ObjectNode toJson() {
            ObjectNode o = MAPPER.createObjectNode().put("cypher", cypher);
            o.set("params", MAPPER.valueToTree(params));
            return o;
        }
    }

    public static CompiledQuery compile(QueryPlanner.PhysicalQuery q) {
        Model m = q.model();
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder c = new StringBuilder();

        // what goes through joins
        Set<String> joins = new LinkedHashSet<>();
        List<String> paths = new ArrayList<>();
        q.dims().forEach(d -> { paths.add(d.path()); if (d.displayPath() != null) paths.add(d.displayPath()); });
        if (q.fields() != null) q.fields().forEach(f -> { if (f.field() != null) paths.add(f.field()); });
        q.conditions().forEach(x -> paths.add(x.field()));
        q.aggs().forEach(a -> { if (a.measure().field() != null) paths.add(a.measure().field()); });
        for (String p : paths) { String j = joinOf(m, p); if (j != null) joins.add(j); }

        List<String> pre = new ArrayList<>(), post = new ArrayList<>();
        for (Condition x : q.conditions())
            (joinOf(m, x.field()) != null ? post : pre).add(x.toCypher(ref(m, x.field()), param(params, x.value())));
        if (q.range() != null) {
            params.put("start", q.range().startIso());
            params.put("end", q.range().endIso());
            String f = ref(m, q.rangeField());
            pre.add(f + " >= datetime($start)");
            pre.add(f + " < datetime($end)");
        }
        c.append("MATCH (").append(NODE).append(':').append(ident(m.node())).append(')');
        if (!pre.isEmpty()) c.append("\nWHERE ").append(String.join(" AND ", pre));
        for (String name : joins) {
            Model.Join j = m.joins().get(name);
            String rel = "-[:" + ident(j.rel()) + "]-";
            c.append("\nOPTIONAL MATCH (").append(NODE).append(')').append("in".equals(j.dir()) ? "<" + rel : rel + ">")
                    .append('(').append(ident(name)).append(':').append(ident(j.node())).append(')');
        }
        if (!post.isEmpty()) {
            List<String> carried = new ArrayList<>(List.of(NODE));
            carried.addAll(joins);
            c.append("\nWITH ").append(String.join(", ", carried)).append("\nWHERE ").append(String.join(" AND ", post));
        }
        if (q.fields() != null) records(q, c, params);
        else aggregate(q, c, params);
        return new CompiledQuery(c.toString(), params);
    }

    private static void aggregate(QueryPlanner.PhysicalQuery q, StringBuilder c, Map<String, Object> params) {
        Model m = q.model();
        List<String> items = new ArrayList<>();
        for (QueryPlanner.DimRef d : q.dims()) {
            String v = ref(m, d.path());
            items.add((d.grain() != null ? "datetime.truncate('" + grain(d.grain()) + "', " + v + ")" : v) + " AS " + ident(d.id()));
            if (d.displayPath() != null) items.add(ref(m, d.displayPath()) + " AS " + ident(d.displayColumn()));
        }
        for (QueryPlanner.Agg a : q.aggs()) items.add(aggregation(m, a, params) + " AS " + ident(a.alias()));
        c.append("\nRETURN ").append(String.join(", ", items));
    }

    /** One measure's aggregation; a measure's own segments make it conditional (CASE WHEN), so several share one pass. */
    private static String aggregation(Model m, QueryPlanner.Agg a, Map<String, Object> params) {
        Model.Measure ms = a.measure();
        String when = null;
        if (!a.segments().isEmpty()) {
            List<String> preds = new ArrayList<>();
            a.segments().forEach(s -> m.segments().get(s).where().forEach(x -> preds.add(x.toCypher(ref(m, x.field()), param(params, x.value())))));
            when = Condition.and(preds);
        }
        if (ms.agg().equals("count")) return when == null ? "count(*)" : "sum(CASE WHEN " + when + " THEN 1 ELSE 0 END)";
        String value = ms.expr() != null ? expr(ms.expr()) : ref(m, ms.field());
        if (when != null) value = "CASE WHEN " + when + " THEN " + value + (ms.agg().equals("sum") ? " ELSE 0 END" : " END");
        return switch (ms.agg()) {
            case "sum" -> "sum(" + value + ")";
            case "avg" -> "avg(" + value + ")";
            case "min" -> "min(" + value + ")";
            case "max" -> "max(" + value + ")";
            case "countDistinct" -> "count(DISTINCT " + value + ")";
            default -> throw new IllegalStateException("unchecked agg " + ms.agg());
        };
    }

    private static void records(QueryPlanner.PhysicalQuery q, StringBuilder c, Map<String, Object> params) {
        Model m = q.model();
        List<String> items = new ArrayList<>();
        for (Model.RecordField f : q.fields()) items.add((f.expr() != null ? expr(f.expr()) : ref(m, f.field())) + " AS " + ident(f.id()));
        c.append("\nRETURN ").append(String.join(", ", items));
        if (!q.sort().isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (Model.SortKey s : q.sort()) {
                String by = ident(s.by());
                // MongoDB sorted a missing value below every other: first ascending, last descending
                keys.add(by + " IS NULL" + (s.descending() ? "" : " DESC"));
                keys.add(by + (s.descending() ? " DESC" : ""));
            }
            // a stable tie-break, so equal sort keys always come back in the same order
            String key = m.records().key();
            if (key != null && q.sort().stream().noneMatch(s -> s.by().equals(key))) keys.add(NODE + "." + ident(key));
            c.append("\nORDER BY ").append(String.join(", ", keys));
        }
        if (q.limit() != null) {
            params.put("limit", q.limit());
            c.append("\nLIMIT $limit");
        }
    }

    /** The Cypher for a model path: {@code n.prop}, or {@code join.prop} through a join. */
    static String ref(Model m, String path) {
        String j = joinOf(m, path);
        if (j == null) return NODE + "." + ident(path);
        return ident(j) + "." + ident(path.substring(j.length() + 1));
    }

    private static String joinOf(Model m, String p) {
        int dot = p.indexOf('.');
        return dot > 0 && m.joins().containsKey(p.substring(0, dot)) ? p.substring(0, dot) : null;
    }

    /** A model expression: Cypher over the model's node {@code n}, checked by the model validator. */
    private static String expr(JsonNode e) { return "(" + e.asText() + ")"; }

    private static String grain(String g) {
        if (!Set.of("month", "quarter", "year").contains(g)) throw new IllegalStateException("unchecked grain " + g);
        return g;
    }

    /** Adds a condition's value as the next parameter and returns its name. */
    private static String param(Map<String, Object> params, JsonNode value) {
        String name = "p" + params.keySet().stream().filter(k -> k.matches("p\\d+")).count();
        params.put(name, GraphJson.param(value));
        return name;
    }

    static String ident(String s) {
        if (s == null || !IDENT.matcher(s).matches()) throw new IllegalStateException("not a plain identifier: '" + s + "'");
        return s;
    }
}
