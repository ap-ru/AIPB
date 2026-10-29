package com.aipb.semantic;

import com.aipb.source.SourceReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Answers semantic queries: plans them ({@link QueryPlanner}), compiles each source query to Cypher
 * ({@link CypherCompiler}), runs it through the {@link SourceReader} (on a page, the driver - not MCP), and assembles
 * the {@link ResultSet}:
 * <ol>
 *   <li>merges the source rows on their dimension values (a comparison query's periods are moved forward by its
 *       shift, so last year's March lines up with this year's);</li>
 *   <li>fills every period of a time breakdown, so a quarter with no revenue shows as 0 rather than disappearing;</li>
 *   <li>evaluates each output expression per row (ratios are null when the denominator is 0 or missing - "no credit"
 *       is not "0% utilization");</li>
 *   <li>sorts and limits.</li>
 * </ol>
 */
@Service
public class QueryExecutor {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String NULL_KEY = "\u0000";

    private final SemanticModel model;
    private final SourceReader source;
    private final QueryPlanner planner;

    public QueryExecutor(SemanticModel model, SourceReader source) {
        this.model = model;
        this.source = source;
        this.planner = new QueryPlanner(model);
    }

    public SemanticModel model() { return model; }

    public QueryPlanner planner() { return planner; }

    /** Runs a query for {@code entityId} (null for the whole book), anchored on the source's asOfDate. */
    public ResultSet execute(SemanticQuery q, String entityId) {
        LocalDate asOf = LocalDate.ofInstant(Instant.parse(source.asOfDate()), ZoneOffset.UTC);
        return execute(q, new QueryContext(asOf, entityId));
    }

    public ResultSet execute(SemanticQuery q, QueryContext ctx) {
        QueryPlanner.Plan plan = planner.plan(q, ctx);
        List<ResultSet.Executed> executed = new ArrayList<>();
        List<JsonNode> results = new ArrayList<>();
        boolean truncated = false;
        for (QueryPlanner.PhysicalQuery pq : plan.queries()) {
            CypherCompiler.CompiledQuery cq = CypherCompiler.compile(pq);
            SourceReader.Result r = source.cypher(cq.cypher(), cq.params());
            truncated |= r.truncated();
            executed.add(new ResultSet.Executed(pq.purpose(), pq.model().id(), pq.model().node(), cq.toJson(), r.count(), r.truncated()));
            results.add(r.documents());
        }
        String asOf = ctx.asOf().atStartOfDay().toInstant(ZoneOffset.UTC).toString();
        return plan.records()
                ? records(plan, results.get(0), executed, truncated, asOf)
                : assemble(plan, results, executed, truncated, asOf);
    }

    // ------------------------------------------------------------------ records
    private ResultSet records(QueryPlanner.Plan plan, JsonNode docs, List<ResultSet.Executed> executed, boolean truncated, String asOf) {
        ArrayNode rows = F.arrayNode();
        int n = 0;
        for (JsonNode d : docs) {
            if (n++ >= plan.limit()) break;
            ObjectNode row = rows.addObject();
            for (ResultSet.Column c : plan.fieldColumns()) {
                JsonNode v = d.get(c.name());
                row.set(c.name(), v == null ? F.nullNode() : "date".equals(c.type()) ? normalizeDate(v) : v);
            }
        }
        boolean hasMore = docs.size() > plan.limit();
        return new ResultSet(plan.fieldColumns(), rows, rows.size(), truncated, hasMore, asOf, executed, plan.notes(), plan.applied());
    }

    // ------------------------------------------------------------------ aggregate
    /** One merged row: its dimension values (by column) and the base measure values (by base key). */
    private static final class Merged {
        final Map<String, JsonNode> dims = new LinkedHashMap<>();
        final Map<String, JsonNode> bases = new HashMap<>();
    }

    private ResultSet assemble(QueryPlanner.Plan plan, List<JsonNode> results, List<ResultSet.Executed> executed, boolean truncated, String asOf) {
        Map<List<String>, Merged> merged = new LinkedHashMap<>();
        for (int i = 0; i < plan.queries().size(); i++) {
            QueryPlanner.PhysicalQuery pq = plan.queries().get(i);
            for (JsonNode row : results.get(i)) {
                List<String> key = new ArrayList<>();
                Map<String, JsonNode> values = new LinkedHashMap<>();
                for (QueryPlanner.DimRef d : plan.dims()) {
                    JsonNode v = row.get(d.id());
                    if (d.grain() != null && v != null && !v.isNull()) {
                        LocalDate period = date(v);
                        for (Window.Shift s : pq.shifts()) period = s.forward(period);
                        v = F.textNode(iso(period));
                    }
                    key.add(v == null || v.isNull() ? NULL_KEY : v.asText());
                    values.put(d.id(), v == null ? F.nullNode() : v);
                    if (d.displayColumn() != null) values.put(d.displayColumn(), row.has(d.displayColumn()) ? row.get(d.displayColumn()) : F.nullNode());
                }
                Merged m = merged.computeIfAbsent(key, k -> new Merged());
                values.forEach((c, v) -> { if (!m.dims.containsKey(c) || m.dims.get(c).isNull()) m.dims.put(c, v); });
                for (QueryPlanner.Agg a : pq.aggs()) m.bases.put(a.baseKey(), row.get(a.alias()));
            }
        }
        if (plan.dims().isEmpty() && merged.isEmpty()) merged.put(List.of(), new Merged());
        if (plan.timeDim() != null && plan.densify() != null) densify(plan, merged);

        List<ObjectNode> rows = new ArrayList<>();
        for (Merged m : merged.values()) {
            ObjectNode row = F.objectNode();
            for (ResultSet.Column c : plan.dimColumns()) row.set(c.name(), m.dims.getOrDefault(c.name(), F.nullNode()));
            for (QueryPlanner.Output o : plan.outputs()) row.set(o.column().name(), value(o.expr(), m));
            rows.add(row);
        }
        if (plan.timeDim() != null && plan.densify() != null) {
            String t = plan.timeDim().id();
            Window.Range r = plan.densify();
            rows.removeIf(row -> { JsonNode v = row.get(t); if (v == null || v.isNull()) return false;
                LocalDate d = date(v); return d.isBefore(Window.start(r.start(), plan.timeDim().grain())) || !d.isBefore(r.end()); });
        }
        rows.sort(comparator(plan.sort()));
        ArrayNode out = F.arrayNode();
        int limit = plan.limit() == null ? Integer.MAX_VALUE : plan.limit();
        for (ObjectNode row : rows) { if (out.size() >= limit) break; out.add(row); }
        List<ResultSet.Column> columns = new ArrayList<>(plan.dimColumns());
        plan.outputs().forEach(o -> columns.add(o.column()));
        return new ResultSet(columns, out, out.size(), truncated, rows.size() > limit, asOf, executed, plan.notes(), plan.applied());
    }

    /** Adds the missing periods of the window for every combination of the other dimensions present. */
    private void densify(QueryPlanner.Plan plan, Map<List<String>, Merged> merged) {
        QueryPlanner.DimRef t = plan.timeDim();
        int ti = plan.dims().indexOf(t);
        List<LocalDate> periods = new ArrayList<>();
        for (LocalDate p = Window.start(plan.densify().start(), t.grain()); p.isBefore(plan.densify().end()); p = Window.add(p, t.grain(), 1))
            periods.add(p);
        Map<List<String>, Merged> others = new LinkedHashMap<>();
        for (Map.Entry<List<String>, Merged> e : merged.entrySet()) {
            List<String> rest = new ArrayList<>(e.getKey());
            rest.remove(ti);
            others.putIfAbsent(rest, e.getValue());
        }
        if (others.isEmpty() && plan.dims().size() == 1) others.put(List.of(), new Merged());
        for (Map.Entry<List<String>, Merged> e : others.entrySet()) {
            for (LocalDate p : periods) {
                List<String> key = new ArrayList<>(e.getKey());
                key.add(ti, iso(p));
                merged.computeIfAbsent(key, k -> {
                    Merged m = new Merged();
                    for (QueryPlanner.DimRef d : plan.dims()) {
                        if (d == t) m.dims.put(d.id(), F.textNode(iso(p)));
                        else {
                            m.dims.put(d.id(), e.getValue().dims.getOrDefault(d.id(), F.nullNode()));
                            if (d.displayColumn() != null) m.dims.put(d.displayColumn(), e.getValue().dims.getOrDefault(d.displayColumn(), F.nullNode()));
                        }
                    }
                    return m;
                });
            }
        }
    }

    /** An output value: a base measure as the source returned it, or a derived figure. */
    private JsonNode value(QueryPlanner.Expr e, Merged m) {
        if (e instanceof QueryPlanner.Base b) {
            JsonNode v = m.bases.get(b.key());
            if (v != null && v.isNumber()) return v;
            return b.additive() ? F.numberNode(0) : F.nullNode();
        }
        Double d = eval(e, m);
        return d == null || d.isNaN() || d.isInfinite() ? F.nullNode() : F.numberNode(d);
    }

    private Double eval(QueryPlanner.Expr e, Merged m) {
        return switch (e) {
            case QueryPlanner.Base b -> {
                JsonNode v = m.bases.get(b.key());
                yield v != null && v.isNumber() ? v.asDouble() : b.additive() ? 0.0 : null;
            }
            case QueryPlanner.Ratio r -> {
                Double n = eval(r.numerator(), m), d = eval(r.denominator(), m);
                yield n == null || d == null || d == 0 ? null : n / d;
            }
            case QueryPlanner.Diff df -> {
                Double l = eval(df.left(), m), r = eval(df.right(), m);
                yield l == null || r == null ? null : l - r;
            }
            case QueryPlanner.Change c -> {
                Double cur = eval(c.current(), m), prior = eval(c.prior(), m);
                yield cur == null || prior == null || prior == 0 ? null : (cur - prior) / Math.abs(prior);
            }
            case QueryPlanner.Add a -> {
                Double sum = null;
                for (QueryPlanner.Expr p : a.parts()) { Double v = eval(p, m); if (v != null) sum = (sum == null ? 0 : sum) + v; }
                yield sum;
            }
        };
    }

    // ------------------------------------------------------------------ helpers
    static Comparator<ObjectNode> comparator(List<SemanticQuery.Sort> sort) {
        Comparator<ObjectNode> c = (a, b) -> 0;
        for (SemanticQuery.Sort s : sort) {
            Comparator<ObjectNode> one = (a, b) -> compare(a.get(s.by()), b.get(s.by()), s.descending());
            c = c.thenComparing(one);
        }
        return c;
    }

    /** Nulls last in both directions; numbers numerically; everything else as text. */
    private static int compare(JsonNode a, JsonNode b, boolean desc) {
        boolean an = a == null || a.isNull(), bn = b == null || b.isNull();
        if (an || bn) return an == bn ? 0 : an ? 1 : -1;
        int r = a.isNumber() && b.isNumber() ? Double.compare(a.asDouble(), b.asDouble()) : a.asText().compareTo(b.asText());
        return desc ? -r : r;
    }

    private static LocalDate date(JsonNode v) { return LocalDate.ofInstant(Instant.parse(v.asText()), ZoneOffset.UTC); }

    private static String iso(LocalDate d) { return d.atStartOfDay().toInstant(ZoneOffset.UTC).toString(); }

    private static JsonNode normalizeDate(JsonNode v) {
        if (!v.isTextual()) return v;
        try { return F.textNode(Instant.parse(v.asText()).toString()); } catch (DateTimeParseException e) { return v; }
    }
}
