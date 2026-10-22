package com.aipb.semantic;

import com.aipb.semantic.SemanticQueryException.Issue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Validates a {@link SemanticQuery} against the semantic model and plans it: which per-model source queries to run
 * ({@link PhysicalQuery}, compiled by {@link PipelineCompiler}) and how to combine their rows into the answer
 * ({@link Output} expressions over base measures, evaluated by {@link QueryExecutor}).
 *
 * <p>Every requested metric expands into base measures, each with its effective window and comparison shifts.
 * Bases are grouped by (model, window, shift): one source query each, grouped by the requested dimensions. Because
 * dimensions are conformed, rows from different models line up on the same dimension values - which is what lets one
 * query mix revenue (revenue_monthly) with wallet size (clients), or this year with last year.
 *
 * <p>All problems are collected and reported together as a {@link SemanticQueryException}, each saying what to change.
 */
public final class QueryPlanner {

    private final SemanticModel sm;

    public QueryPlanner(SemanticModel sm) { this.sm = sm; }

    // ------------------------------------------------------------------ plan types
    /** A dimension as one model stores it: value path (or the time field, truncated to {@code grain}) and label path. */
    public record DimRef(String id, String path, String grain, String displayColumn, String displayPath) {}

    /** One measure inside a source query, under a generated alias, with the segments not pushed into the $match. */
    public record Agg(String alias, String baseKey, Model.Measure measure, List<String> segments) {}

    /**
     * One source query against one model. {@code conditions} are ANDed (entity scope, filters, segments);
     * {@code range} filters {@code rangeField}; {@code shifts} say how far behind the asked window it lies (compare).
     * Aggregate queries have {@code dims} and {@code aggs}; records queries have {@code fields}, {@code sort} and {@code limit}.
     */
    public record PhysicalQuery(String purpose, Model model, List<DimRef> dims, List<Agg> aggs, List<Model.RecordField> fields,
                                List<Condition> conditions, Window.Range range, String rangeField, List<Window.Shift> shifts,
                                List<Model.SortKey> sort, Integer limit) {}

    /** How an output value is computed from base measures, row by row. */
    public sealed interface Expr permits Base, Ratio, Diff, Change, Add {}
    /** A base measure value; {@code additive}: missing means 0 (sums, counts), otherwise missing means unknown. */
    public record Base(String key, boolean additive) implements Expr {}
    public record Ratio(Expr numerator, Expr denominator) implements Expr {}
    public record Diff(Expr left, Expr right) implements Expr {}
    public record Change(Expr current, Expr prior) implements Expr {}
    public record Add(List<Expr> parts) implements Expr {}

    /** One output column and the expression that computes it. */
    public record Output(ResultSet.Column column, Expr expr) {}

    /**
     * The plan. {@code columns} are the dimension columns then the outputs' columns; {@code timeDim} is the time
     * dimension (if any) and {@code densify} the range whose every period must appear (missing additive periods are 0).
     */
    public record Plan(SemanticQuery query, List<PhysicalQuery> queries, List<DimRef> dims, List<ResultSet.Column> dimColumns,
                       List<Output> outputs, List<ResultSet.Column> fieldColumns, DimRef timeDim, Window.Range densify,
                       List<SemanticQuery.Sort> sort, Integer limit, List<String> notes, ResultSet.Applied applied) {
        public boolean records() { return query.records(); }
    }

    // ------------------------------------------------------------------ entry
    public Plan plan(SemanticQuery q, QueryContext ctx) {
        List<Issue> issues = new ArrayList<>();
        if (q == null) throw new SemanticQueryException(List.of(new Issue("query", "", "the query is empty")));
        String kind = q.kind() == null ? "aggregate" : q.kind();
        if (!kind.equals("aggregate") && !kind.equals("records"))
            issues.add(new Issue("kind", "kind", "kind must be aggregate or records; got '" + kind + "'"));
        if (!Set.of("inherit", "book", "entity").contains(q.scopeOrDefault()))
            issues.add(new Issue("scope", "scope", "scope must be inherit, book or entity"));
        if (q.scopeOrDefault().equals("entity") && ctx.entityId() == null)
            issues.add(new Issue("scope", "scope", "this query needs a selected client (scope entity), and the page has none"));
        if (q.window() != null) q.window().problems().forEach(p -> issues.add(new Issue("window", "window", p)));
        if (q.limit() != null && (q.limit() < 1 || q.limit() > SemanticQuery.MAX_LIMIT))
            issues.add(new Issue("limit", "limit", "limit must be between 1 and " + SemanticQuery.MAX_LIMIT));
        q.sortOrEmpty().forEach(s -> {
            if (s.dir() != null && !Set.of("asc", "desc").contains(s.dir())) issues.add(new Issue("sort", "sort", "sort dir must be asc or desc"));
        });
        if (q.period() != null && !Set.of("page", "fixed").contains(q.period()))
            issues.add(new Issue("period", "period", "period must be page (follow the page's period) or fixed"));
        if (ctx.period() != null) ctx.period().problems().forEach(p -> issues.add(new Issue("page_period", "period", "the page's period: " + p)));
        if (!issues.isEmpty()) throw new SemanticQueryException(issues);
        // a query that follows the page takes its period (on the query's own date, e.g. maturity, if it names one)
        if (q.followsPagePeriod() && ctx.period() != null)
            q = q.withWindow(q.window() != null && q.window().on() != null && ctx.period().on() == null ? ctx.period().onDate(q.window().on()) : ctx.period());
        Plan p = kind.equals("records") ? planRecords(q, ctx, issues) : planAggregate(q, ctx, issues);
        if (!issues.isEmpty()) throw new SemanticQueryException(issues);
        return p;
    }

    // ------------------------------------------------------------------ aggregate
    private Plan planAggregate(SemanticQuery q, QueryContext ctx, List<Issue> issues) {
        List<String> notes = new ArrayList<>();
        if (q.metricsOrEmpty().isEmpty()) issues.add(new Issue("metrics", "metrics", "an aggregate query needs at least one metric"));
        if (q.model() != null || !q.fieldsOrEmpty().isEmpty())
            issues.add(new Issue("kind", "model", "model and fields belong to records queries; an aggregate names metrics"));
        for (int i = 0; i < q.metricsOrEmpty().size(); i++) {
            String id = q.metricsOrEmpty().get(i);
            if (!sm.isMetric(id)) issues.add(new Issue("metric", "metrics[" + i + "]", "unknown metric '" + id + "'; metrics: "
                    + String.join(", ", allMetricIds())));
        }
        if (q.compare() != null && !Window.COMPARES.contains(q.compare()))
            issues.add(new Issue("compare", "compare", "compare must be priorYear or priorPeriod"));
        if (!issues.isEmpty()) return null;

        // which models the request touches, and the dimensions as each binds them
        Set<Model> models = new LinkedHashSet<>();
        q.metricsOrEmpty().forEach(id -> modelsOf(id, models));
        List<String> dimIds = q.dimensionsOrEmpty();
        long timeDims = dimIds.stream().filter(d -> sm.dimension(d) != null && sm.dimension(d).isTime()).count();
        if (timeDims > 1) issues.add(new Issue("dimension", "dimensions", "break down by at most one of month, quarter, year"));
        if (new HashSet<>(dimIds).size() != dimIds.size()) issues.add(new Issue("dimension", "dimensions", "a dimension is listed twice"));
        Map<Model, List<DimRef>> dimsByModel = new LinkedHashMap<>();
        for (Model m : models) dimsByModel.put(m, new ArrayList<>());
        for (int i = 0; i < dimIds.size(); i++) {
            String id = dimIds.get(i);
            Dimension d = sm.dimension(id);
            if (d == null) { issues.add(new Issue("dimension", "dimensions[" + i + "]", "unknown dimension '" + id + "'; dimensions: "
                    + String.join(", ", sm.dimensions().keySet()))); continue; }
            for (Model m : models) {
                DimRef ref = dimRef(m, d);
                if (ref == null) issues.add(new Issue("dimension", "dimensions[" + i + "]", d.isTime()
                        ? "'" + m.label() + "' figures are current balances with no history, so they cannot be broken down by " + id
                        : "'" + m.label() + "' figures cannot be broken down by " + id + "; it is available for: "
                        + String.join(", ", available(m))));
                else dimsByModel.get(m).add(ref);
            }
        }
        // filters (on dimensions) become conditions per model
        Map<Model, List<Condition>> conditions = new LinkedHashMap<>();
        for (Model m : models) conditions.put(m, new ArrayList<>(entityScope(q, ctx, m)));
        for (int i = 0; i < q.filtersOrEmpty().size(); i++) {
            SemanticQuery.Filter f = q.filtersOrEmpty().get(i);
            String at = "filters[" + i + "]";
            Dimension d = sm.dimension(f.on());
            if (d == null || d.isTime()) { issues.add(new Issue("filter", at, "filter on unknown dimension '" + f.on()
                    + "' (use window for time)")); continue; }
            checkFilter(f, d, at, issues);
            for (Model m : models) {
                Model.Binding b = m.dimensions().get(d.id());
                if (b == null) issues.add(new Issue("filter", at, "'" + m.label() + "' figures cannot be filtered by " + d.id()));
                else conditions.get(m).add(new Condition(b.field(), f.op(), f.value()));
            }
        }
        if (!q.segmentsOrEmpty().isEmpty()) {
            if (models.size() != 1) issues.add(new Issue("segment", "segments", "segments apply to one model; these metrics span "
                    + models.stream().map(Model::id).toList()));
            else {
                Model m = models.iterator().next();
                for (String s : q.segmentsOrEmpty()) {
                    if (!m.segments().containsKey(s)) issues.add(new Issue("segment", "segments", "'" + m.label() + "' has no segment '" + s
                            + "'; segments: " + String.join(", ", m.segments().keySet())));
                    else conditions.get(m).addAll(m.segments().get(s).where());
                }
            }
        }
        List<String> appliedFilters = new ArrayList<>();
        if (q.takesPageFilters()) for (SemanticQuery.Filter f : ctx.filters()) {
            Dimension d = pageFilterDimension(f, issues);
            if (d == null) continue;
            List<String> cannot = models.stream().filter(m -> !m.dimensions().containsKey(d.id())).map(Model::label).toList();
            if (!cannot.isEmpty()) {
                note(notes, "Not filtered by " + d.label().toLowerCase(Locale.ROOT) + ": " + String.join(" and ", cannot)
                        + " figures cannot be filtered that way.");
                continue;
            }
            models.forEach(m -> conditions.get(m).add(new Condition(m.dimensions().get(d.id()).field(), f.op(), f.value())));
            appliedFilters.add(filterText(d, f));
        }
        if (!issues.isEmpty()) return null;

        // outputs: each metric (and its prior value) as an expression over bases; bases grouped into source queries
        Map<String, BaseSpec> bases = new LinkedHashMap<>();
        List<Output> outputs = new ArrayList<>();
        Window.Shift compareShift = null;
        if (q.compare() != null) {
            compareShift = q.window() == null ? (q.compare().equals("priorYear") ? new Window.Shift("year", 1) : null) : q.window().shift(q.compare());
            if (compareShift == null) issues.add(new Issue("compare", "compare", "compare " + q.compare() + " needs a window with a prior period"));
        }
        for (int i = 0; i < q.metricsOrEmpty().size(); i++) {
            String id = q.metricsOrEmpty().get(i);
            Expr e = expr(id, q.window(), List.of(), bases, ctx, issues, "metrics[" + i + "]", notes);
            outputs.add(new Output(metricColumn(id, id, "metric", windowText(e, List.of(), bases, ctx), null), e));
        }
        if (compareShift != null) {
            for (int i = 0; i < q.metricsOrEmpty().size(); i++) {
                String id = q.metricsOrEmpty().get(i);
                if (sm.derivedMetric(id) != null && sm.derivedMetric(id).kind().equals("change")) {
                    note(notes, id + " is already a change against a prior period, so it has no prior value of its own.");
                    continue;
                }
                Expr e = expr(id, q.window(), List.of(compareShift), bases, ctx, issues, "metrics[" + i + "]", notes);
                outputs.add(new Output(metricColumn(id + "Prior", id, "prior", windowText(e, List.of(compareShift), bases, ctx), compareShift), e));
            }
        }
        if (!issues.isEmpty()) return null;

        Map<String, List<BaseSpec>> groups = bases.values().stream()
                .collect(Collectors.groupingBy(b -> b.model.id() + "|" + b.rangeKey(), LinkedHashMap::new, Collectors.toList()));
        List<PhysicalQuery> queries = new ArrayList<>();
        for (List<BaseSpec> group : groups.values()) {
            BaseSpec first = group.get(0);
            Model m = first.model;
            // segments every measure shares go into the $match (no zero rows for other groups); the rest filter per measure
            Set<String> common = new LinkedHashSet<>(group.get(0).measure.segments());
            group.forEach(b -> common.retainAll(b.measure.segments()));
            List<Condition> conds = new ArrayList<>(conditions.get(m));
            common.forEach(s -> conds.addAll(m.segments().get(s).where()));
            List<Agg> aggs = new ArrayList<>();
            for (BaseSpec b : group) {
                List<String> rest = b.measure.segments().stream().filter(s -> !common.contains(s)).toList();
                aggs.add(new Agg("m" + aggs.size(), b.key, b.measure, rest));
            }
            String purpose = m.label() + ": " + group.stream().map(b -> b.measure.id()).collect(Collectors.joining(", "))
                    + (first.window == null ? "" : " over " + first.window.describe()) + (first.shifts.isEmpty() ? "" : " - "
                    + first.shifts.stream().map(Window.Shift::describe).collect(Collectors.joining(", then ")));
            queries.add(new PhysicalQuery(purpose, m, dimsByModel.get(m), aggs, null, conds, first.range, first.rangeField,
                    first.shifts, null, null));
        }

        // dimension columns, densify range, sort
        List<DimRef> dims = queries.get(0).dims();
        List<ResultSet.Column> dimColumns = new ArrayList<>();
        DimRef timeDim = null;
        for (DimRef r : dims) {
            Dimension d = sm.dimension(r.id());
            if (d.isTime()) timeDim = r;
            boolean keyed = d.displayColumn() != null;
            dimColumns.add(new ResultSet.Column(d.id(), d.label(), d.type(), d.isTime() ? "date" : null,
                    ResultSet.defaultFormat(d.type(), d.isTime() ? "date" : null, d.tones() != null), d.grain(), "dimension", null,
                    d.tones(), null, keyed ? Boolean.TRUE : null, new ResultSet.Lineage("dimension", d.id(), null,
                    models.stream().map(Model::id).toList(), d.description(), null, null)));
            if (keyed) dimColumns.add(new ResultSet.Column(d.displayColumn(), d.displayLabel() == null ? d.label() : d.displayLabel(),
                    "string", null, "text", null, "display", null, null, null, null, null));
        }
        Window.Range densify = null;
        if (timeDim != null) densify = bases.values().stream().filter(b -> b.shifts.isEmpty() && b.range != null && b.model.time() != null)
                .map(b -> b.range).findFirst().orElse(null);

        Set<String> sortable = new LinkedHashSet<>();
        dimColumns.forEach(c -> sortable.add(c.name()));
        outputs.forEach(o -> sortable.add(o.column().name()));
        List<SemanticQuery.Sort> sort = q.sortOrEmpty();
        for (int i = 0; i < sort.size(); i++)
            if (!sortable.contains(sort.get(i).by())) issues.add(new Issue("sort", "sort[" + i + "]", "cannot sort by '" + sort.get(i).by()
                    + "'; sort by one of " + sortable));
        if (sort.isEmpty()) {
            if (timeDim != null) sort = List.of(new SemanticQuery.Sort(timeDim.id(), "asc"));
            else if (!dims.isEmpty()) sort = List.of(new SemanticQuery.Sort(outputs.get(0).column().name(), "desc"));
        }
        return new Plan(q, queries, dims, dimColumns, outputs, List.of(), timeDim, densify, sort, q.limit(), notes,
                applied(q, ctx, models, null, appliedFilters));
    }

    /** One base measure value: which measure, over which range, shifted how far back. */
    private record BaseSpec(String key, Model model, Model.Measure measure, Window window, Window.Range range, String rangeField,
                            List<Window.Shift> shifts) {
        String rangeKey() {
            return (window == null ? "now" : window.key()) + "|" + shifts.stream().map(s -> s.unit() + s.amount()).collect(Collectors.joining(","));
        }
    }

    /** The expression computing metric {@code id} over {@code window}, shifted back by {@code shifts}; registers its bases. */
    private Expr expr(String id, Window window, List<Window.Shift> shifts, Map<String, BaseSpec> bases, QueryContext ctx,
                      List<Issue> issues, String at, List<String> notes) {
        SemanticModel.MeasureRef mr = sm.measure(id);
        if (mr != null) {
            Model m = mr.model();
            Window eff = effectiveWindow(m, window, issues, at, notes, id);
            List<Window.Shift> sh = eff == null ? List.of() : shifts;
            if (eff == null && !shifts.isEmpty())
                note(notes, id + " is a current figure (" + m.label() + " has no history), so its prior value is today's.");
            Window.Range range = null;
            String rangeField = null;
            if (eff != null) {
                rangeField = m.dateField(eff.on());
                boolean closed = m.time() != null && m.time().closedOnly() && rangeField.equals(m.time().field());
                range = eff.resolve(ctx.asOf(), closed);
                for (Window.Shift s : sh) range = range.minus(s);
            }
            BaseSpec b = new BaseSpec(null, m, mr.measure(), eff, range, rangeField, sh);
            String key = id + "@" + b.rangeKey();
            bases.putIfAbsent(key, new BaseSpec(key, m, mr.measure(), eff, range, rangeField, sh));
            return new Base(key, mr.measure().additive());
        }
        DerivedMetric d = sm.derivedMetric(id);
        return switch (d.kind()) {
            case "ratio" -> new Ratio(operand(d.numerator(), window, shifts, bases, ctx, issues, at, notes),
                    operand(d.denominator(), window, shifts, bases, ctx, issues, at, notes));
            case "difference" -> new Diff(operand(d.left(), window, shifts, bases, ctx, issues, at, notes),
                    operand(d.right(), window, shifts, bases, ctx, issues, at, notes));
            case "same" -> operand(d.of(), window, shifts, bases, ctx, issues, at, notes);
            case "change" -> {
                Window w = d.of() instanceof DerivedMetric.Ref r && r.window() != null ? r.window() : window;
                Window.Shift s = d.compare().equals("priorYear") ? new Window.Shift("year", 1) : w == null ? null : w.shift(d.compare());
                if (s == null) {
                    issues.add(new Issue("window", at, id + " compares with the prior period, so it needs a window (e.g. ytd)"));
                    yield new Add(List.of());
                }
                List<Window.Shift> prior = new ArrayList<>(shifts);
                prior.add(s);
                yield new Change(operand(d.of(), window, shifts, bases, ctx, issues, at, notes),
                        operand(d.of(), window, prior, bases, ctx, issues, at, notes));
            }
            default -> throw new IllegalStateException("unchecked metric kind " + d.kind());
        };
    }

    private Expr operand(DerivedMetric.Operand o, Window window, List<Window.Shift> shifts, Map<String, BaseSpec> bases,
                         QueryContext ctx, List<Issue> issues, String at, List<String> notes) {
        if (o instanceof DerivedMetric.Sum s)
            return new Add(s.of().stream().map(p -> operand(p, window, shifts, bases, ctx, issues, at, notes)).toList());
        DerivedMetric.Ref r = (DerivedMetric.Ref) o;
        return expr(r.metric(), r.window() != null ? r.window() : window, shifts, bases, ctx, issues, at, notes);
    }

    /** The window as it applies to a model: none for all-time and for current-state models (with a note), else the window. */
    private Window effectiveWindow(Model m, Window w, List<Issue> issues, String at, List<String> notes, String metric) {
        if (w == null || w.type().equals("all")) return null;
        if (w.on() != null) {
            if (m.dateField(w.on()) != null) return w;
            if (m.snapshot()) { note(notes, metric + " is a current figure; the window on '" + w.on() + "' does not apply to it."); return null; }
            issues.add(new Issue("window", at, "'" + m.label() + "' has no date '" + w.on() + "'; its dates: "
                    + String.join(", ", m.dates().keySet())));
            return null;
        }
        if (m.snapshot()) { note(notes, metric + " is a current figure (as of today); the window does not apply to it."); return null; }
        return w;
    }

    // ------------------------------------------------------------------ records
    private Plan planRecords(SemanticQuery q, QueryContext ctx, List<Issue> issues) {
        List<String> notes = new ArrayList<>();
        Model m = q.model() == null ? null : sm.model(q.model());
        if (m == null || m.records() == null) {
            issues.add(new Issue("model", "model", "records need a model that lists records: " + String.join(", ",
                    sm.models().values().stream().filter(x -> x.records() != null).map(Model::id).toList())));
            return null;
        }
        if (!q.metricsOrEmpty().isEmpty() || !q.dimensionsOrEmpty().isEmpty() || q.compare() != null)
            issues.add(new Issue("kind", "metrics", "metrics, dimensions and compare belong to aggregate queries; records name fields"));
        Model.Records rec = m.records();
        List<String> ids = q.fieldsOrEmpty().isEmpty() ? rec.fields().stream().map(Model.RecordField::id).toList() : q.fieldsOrEmpty();
        List<Model.RecordField> fields = new ArrayList<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            Model.RecordField f = rec.field(ids.get(i));
            if (f == null) issues.add(new Issue("field", "fields[" + i + "]", "'" + m.label() + "' records have no field '" + ids.get(i)
                    + "'; fields: " + String.join(", ", rec.fields().stream().map(Model.RecordField::id).toList())));
            else if (!fields.contains(f)) fields.add(f);
        }
        // a per-row unit needs its unit column alongside
        for (Model.RecordField f : List.copyOf(fields)) {
            if (f.unitFrom() == null) continue;
            Model.RecordField unitField = rec.fields().stream().filter(o -> f.unitFrom().field().equals(o.field())).findFirst().orElseThrow();
            if (!fields.contains(unitField)) { fields.add(unitField); hidden.add(unitField.id()); }
        }
        List<Condition> conds = new ArrayList<>(entityScope(q, ctx, m));
        for (int i = 0; i < q.filtersOrEmpty().size(); i++) {
            SemanticQuery.Filter f = q.filtersOrEmpty().get(i);
            String at = "filters[" + i + "]";
            Model.RecordField rf = rec.field(f.on());
            Dimension d = sm.dimension(f.on());
            if (rf != null) {
                if (rf.field() == null) { issues.add(new Issue("filter", at, "'" + f.on() + "' is computed and cannot be filtered on")); continue; }
                checkFilter(f, rf.dimension() == null ? null : sm.dimension(rf.dimension()), at, issues);
                conds.add(new Condition(rf.field(), f.op(), f.value()));
            } else if (d != null && !d.isTime() && m.dimensions().containsKey(d.id())) {
                checkFilter(f, d, at, issues);
                conds.add(new Condition(m.dimensions().get(d.id()).field(), f.op(), f.value()));
            } else {
                issues.add(new Issue("filter", at, "'" + m.label() + "' records cannot be filtered on '" + f.on() + "'"));
            }
        }
        for (String s : q.segmentsOrEmpty()) {
            if (!m.segments().containsKey(s)) issues.add(new Issue("segment", "segments", "'" + m.label() + "' has no segment '" + s
                    + "'; segments: " + String.join(", ", m.segments().keySet())));
            else conds.addAll(m.segments().get(s).where());
        }
        List<String> appliedFilters = new ArrayList<>();
        if (q.takesPageFilters()) for (SemanticQuery.Filter f : ctx.filters()) {
            Dimension d = pageFilterDimension(f, issues);
            if (d == null) continue;
            Model.Binding b = m.dimensions().get(d.id());
            if (b == null) { note(notes, "Not filtered by " + d.label().toLowerCase(Locale.ROOT) + ": " + m.label() + " cannot be filtered that way."); continue; }
            conds.add(new Condition(b.field(), f.op(), f.value()));
            appliedFilters.add(filterText(d, f));
        }
        Window eff = effectiveWindow(m, q.window(), issues, "window", notes, m.id());
        Window.Range range = null;
        String rangeField = null;
        if (eff != null) {
            rangeField = m.dateField(eff.on());
            range = eff.resolve(ctx.asOf(), m.time() != null && m.time().closedOnly() && rangeField.equals(m.time().field()));
        }
        List<Model.SortKey> sort = new ArrayList<>();
        List<SemanticQuery.Sort> asked = q.sortOrEmpty();
        if (asked.isEmpty()) sort.addAll(rec.defaultSort());
        for (int i = 0; i < asked.size(); i++) {
            Model.RecordField f = rec.field(asked.get(i).by());
            if (f == null) issues.add(new Issue("sort", "sort[" + i + "]", "cannot sort by '" + asked.get(i).by() + "'; not a record field"));
            else sort.add(new Model.SortKey(f.id(), asked.get(i).dir() == null ? "asc" : asked.get(i).dir()));
        }
        for (Model.SortKey s : sort) {
            Model.RecordField f = rec.field(s.by());
            if (f != null && !fields.contains(f)) { fields.add(f); hidden.add(f.id()); }
        }
        if (!issues.isEmpty()) return null;
        int limit = q.limit() == null ? SemanticQuery.DEFAULT_RECORD_LIMIT : q.limit();
        PhysicalQuery pq = new PhysicalQuery(m.label() + " records" + (eff == null ? "" : " over " + eff.describe()), m, List.of(), List.of(),
                fields, conds, range, rangeField, List.of(), sort, limit + 1);
        List<ResultSet.Column> cols = new ArrayList<>();
        for (Model.RecordField f : fields) {
            Dimension d = f.dimension() == null ? null : sm.dimension(f.dimension());
            Map<String, String> tones = d == null ? null : d.tones();
            ResultSet.UnitFrom uf = null;
            if (f.unitFrom() != null) {
                String col = rec.fields().stream().filter(o -> f.unitFrom().field().equals(o.field())).findFirst().orElseThrow().id();
                uf = new ResultSet.UnitFrom(col, f.unitFrom().map());
            }
            cols.add(new ResultSet.Column(f.id(), f.label(), f.type(), f.unit(), uf != null ? null : ResultSet.defaultFormat(f.type(), f.unit(), tones != null),
                    null, "field", null, tones, uf, hidden.contains(f.id()) ? Boolean.TRUE : null,
                    new ResultSet.Lineage("field", m.id() + "." + f.id(), m.version(), List.of(m.id()),
                            f.description() != null ? f.description() : d != null ? d.description() : f.label(), f.sources(),
                            eff == null ? null : eff.describe() + " (" + range.describe() + ")")));
        }
        return new Plan(q, List.of(pq), List.of(), List.of(), List.of(), cols, null, null,
                sort.stream().map(s -> new SemanticQuery.Sort(s.by(), s.dir())).toList(), limit, notes,
                applied(q, ctx, Set.of(m), eff, appliedFilters));
    }

    // ------------------------------------------------------------------ helpers
    /** A page filter's dimension, checked like a query filter; null (with an issue) when it is not a usable dimension. */
    private Dimension pageFilterDimension(SemanticQuery.Filter f, List<Issue> issues) {
        Dimension d = f.on() == null ? null : sm.dimension(f.on());
        String at = "page filter " + f.on();
        if (d == null || d.isTime()) { issues.add(new Issue("page_filter", at, "the page filters on unknown dimension '" + f.on() + "'")); return null; }
        int before = issues.size();
        checkFilter(f, d, at, issues);
        return issues.size() == before ? d : null;
    }

    private static String filterText(Dimension d, SemanticQuery.Filter f) {
        String values = f.value().isArray() ? String.join(", ", new Condition(d.id(), f.op(), f.value()).textValues()) : f.value().asText();
        return d.label() + (f.op().equals("ne") || f.op().equals("nin") ? " not " : ": ") + values;
    }

    /**
     * The context as it applied: whose figures, the period in words with its dates (or "current" when every figure is
     * a current balance), and the page filters that were applied. {@code window} is the records query's effective window.
     */
    private ResultSet.Applied applied(SemanticQuery q, QueryContext ctx, Collection<Model> models, Window recordsWindow, List<String> filters) {
        boolean entity = !q.scopeOrDefault().equals("book") && ctx.entityId() != null;
        Window w = q.records() ? recordsWindow : q.window();
        String period;
        Model timed = models.stream().filter(m -> w != null && m.dateField(w.on()) != null).findFirst().orElse(null);
        if (timed != null && !w.type().equals("all")) {
            String field = timed.dateField(w.on());
            boolean closed = timed.time() != null && timed.time().closedOnly() && field.equals(timed.time().field());
            period = w.describe() + " (" + w.resolve(ctx.asOf(), closed).describe() + ")";
        } else if (models.stream().allMatch(Model::snapshot)) {
            period = "current, as of " + ctx.asOf();
        } else {
            period = "all time";
        }
        return new ResultSet.Applied(entity ? "entity" : "book", entity ? ctx.entityId() : null, period, List.copyOf(filters));
    }

    private List<Condition> entityScope(SemanticQuery q, QueryContext ctx, Model m) {
        if (q.scopeOrDefault().equals("book") || ctx.entityId() == null || m.entity() == null) return List.of();
        return List.of(new Condition(m.entity(), "eq", com.fasterxml.jackson.databind.node.TextNode.valueOf(ctx.entityId())));
    }

    private void checkFilter(SemanticQuery.Filter f, Dimension d, String at, List<Issue> issues) {
        if (f.op() == null || !Condition.OPS.contains(f.op())) {
            issues.add(new Issue("filter", at, "filter op must be one of " + Condition.OPS)); return;
        }
        JsonNode v = f.value();
        boolean list = f.op().equals("in") || f.op().equals("nin");
        if (v == null || v.isNull() || list != v.isArray()) {
            issues.add(new Issue("filter", at, list ? f.op() + " needs a list of values" : f.op() + " needs a single value")); return;
        }
        if (d != null && d.values() != null && Set.of("eq", "ne", "in", "nin").contains(f.op())) {
            new Condition(f.on(), f.op(), v).textValues().stream().filter(x -> !d.values().contains(x)).forEach(x ->
                    issues.add(new Issue("filter_value", at, "'" + x + "' is not a value of " + d.id() + "; values: " + String.join(", ", d.values()))));
        }
    }

    private DimRef dimRef(Model m, Dimension d) {
        if (d.isTime()) return m.time() == null ? null : new DimRef(d.id(), m.time().field(), d.grain(), null, null);
        Model.Binding b = m.dimensions().get(d.id());
        if (b == null) return null;
        return new DimRef(d.id(), b.field(), null, d.displayColumn(), b.display());
    }

    private List<String> available(Model m) {
        List<String> out = new ArrayList<>(m.dimensions().keySet());
        if (m.time() != null) out.addAll(List.of("month", "quarter", "year"));
        return out;
    }

    private void modelsOf(String id, Set<Model> out) {
        if (sm.measure(id) != null) { out.add(sm.measure(id).model()); return; }
        DerivedMetric d = sm.derivedMetric(id);
        if (d != null) SemanticModelValidator.refs(d).forEach(r -> modelsOf(r, out));
    }

    private ResultSet.Column metricColumn(String name, String id, String role, String win, Window.Shift shift) {
        SemanticModel.MeasureRef mr = sm.measure(id);
        DerivedMetric d = sm.derivedMetric(id);
        String label = mr != null ? mr.measure().label() : d.label();
        String unit = mr != null ? mr.measure().unit() : d.unit();
        String polarity = mr != null ? mr.measure().polarity() : d.polarity();
        Set<Model> models = new LinkedHashSet<>();
        modelsOf(id, models);
        List<String> sources = new ArrayList<>();
        sourcesOf(id, sources);
        String definition = mr != null ? mr.measure().description() : d.description() + " (" + formula(d) + ")";
        if (shift != null) {
            label = label + (shift.unit().equals("year") && shift.amount() == 1 ? " (prior year)" : " (prior period)");
            definition = definition + " - over the same window " + shift.describe();
        }
        return new ResultSet.Column(name, label, "number", unit, ResultSet.defaultFormat("number", unit, false), null, role, polarity,
                null, null, null, new ResultSet.Lineage(mr != null ? "measure" : "metric", id, mr != null ? mr.measure().version() : d.version(),
                models.stream().map(Model::id).toList(), definition, sources.stream().distinct().toList(), win));
    }

    /**
     * The time an output covers, from the base measures it actually reads (so a pinned window, a current balance or a
     * closed-month range is described as it ran): e.g. "last 12 months (Oct 1, 2025 - Sep 30, 2026); current, as of
     * 2026-10-16". A change's prior bases are summarized as "vs a year earlier".
     */
    private String windowText(Expr e, List<Window.Shift> shifts, Map<String, BaseSpec> bases, QueryContext ctx) {
        List<BaseSpec> used = new ArrayList<>();
        collectBases(e, bases, used);
        Set<String> parts = new LinkedHashSet<>();
        boolean current = false, prior = false;
        for (BaseSpec b : used) {
            if (b.window == null) { if (b.model.snapshot()) current = true; else parts.add("all time"); continue; }
            if (!b.shifts.equals(shifts)) { prior = true; continue; }
            parts.add(b.window.describe() + " (" + b.range.describe() + ")");
        }
        if (current) parts.add("current, as of " + ctx.asOf());
        String text = String.join("; ", parts);
        if (prior && e instanceof Change) text = text + " vs " + lastShift(used, shifts).describe();
        return text.isEmpty() ? null : text;
    }

    private static Window.Shift lastShift(List<BaseSpec> used, List<Window.Shift> shifts) {
        return used.stream().filter(b -> b.shifts.size() > shifts.size()).map(b -> b.shifts.get(b.shifts.size() - 1)).findFirst().orElseThrow();
    }

    private static void collectBases(Expr e, Map<String, BaseSpec> bases, List<BaseSpec> out) {
        switch (e) {
            case Base b -> out.add(bases.get(b.key()));
            case Ratio r -> { collectBases(r.numerator(), bases, out); collectBases(r.denominator(), bases, out); }
            case Diff d -> { collectBases(d.left(), bases, out); collectBases(d.right(), bases, out); }
            case Change c -> { collectBases(c.current(), bases, out); collectBases(c.prior(), bases, out); }
            case Add a -> a.parts().forEach(p -> collectBases(p, bases, out));
        }
    }

    private void sourcesOf(String id, List<String> out) {
        if (sm.measure(id) != null) { out.addAll(sm.measure(id).measure().sources()); return; }
        DerivedMetric d = sm.derivedMetric(id);
        if (d != null) SemanticModelValidator.refs(d).forEach(r -> sourcesOf(r, out));
    }

    static String formula(DerivedMetric d) {
        return switch (d.kind()) {
            case "ratio" -> text(d.numerator()) + " / " + text(d.denominator());
            case "difference" -> text(d.left()) + " - " + text(d.right());
            case "change" -> "change in " + text(d.of()) + " vs " + (d.compare().equals("priorYear") ? "a year earlier" : "the prior period");
            case "same" -> text(d.of());
            default -> d.kind();
        };
    }

    private static String text(DerivedMetric.Operand o) {
        if (o instanceof DerivedMetric.Sum s) return "(" + s.of().stream().map(QueryPlanner::text).collect(Collectors.joining(" + ")) + ")";
        DerivedMetric.Ref r = (DerivedMetric.Ref) o;
        return r.metric() + (r.window() == null ? "" : " over the " + r.window().describe());
    }

    private List<String> allMetricIds() {
        List<String> out = new ArrayList<>(sm.measures().keySet());
        out.addAll(sm.derived().keySet());
        return out;
    }

    private static void note(List<String> notes, String n) { if (!notes.contains(n)) notes.add(n); }
}
