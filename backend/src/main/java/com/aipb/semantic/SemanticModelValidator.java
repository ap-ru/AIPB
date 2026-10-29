package com.aipb.semantic;

import com.aipb.source.ReadOnlyCypher;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the semantic model. {@link #structural} needs nothing but the model (ids unique, references resolve, no
 * metric cycles, valid aggregations, units, windows, expressions that read only what they declare) and runs on every
 * load. {@link #againstCatalog} checks it against the data dictionary ((:_Catalog)): every label and property it reads
 * exists, every join is a relationship the dictionary declares as many-to-one, and every value it filters on or
 * enumerates is an allowed value - so a renamed property or value in the source is caught, not silently empty.
 */
public final class SemanticModelValidator {

    /** Units the semantic layer can declare. The design system formats each one (multiple = 2.1x, days = 45 days). */
    public static final Set<String> UNITS = Set.of("USD", "ratio", "percent", "count", "date", "none", "multiple", "days");
    static final Set<String> AGGS = Set.of("sum", "count", "avg", "min", "max", "countDistinct");
    static final Set<String> TYPES = Set.of("string", "number", "date", "boolean");
    static final Set<String> GRAINS = Set.of("month", "quarter", "year");
    static final Set<String> POLARITIES = Set.of("higherIsBetter", "lowerIsBetter", "neutral");
    static final Set<String> KINDS = Set.of("ratio", "difference", "change", "same");
    static final Set<String> TONES = Set.of("good", "warn", "bad", "info", "neutral");
    /** A property reference inside an expression: {@code variable.property}. */
    private static final Pattern PROPERTY_REF = Pattern.compile("(?<![\\w.$])([A-Za-z_]\\w*)\\s*\\.\\s*([A-Za-z_]\\w*)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private SemanticModelValidator() {}

    // ------------------------------------------------------------------ structure
    public static List<String> structural(SemanticModel sm) {
        List<String> out = new ArrayList<>();
        sm.dimensions().values().forEach(d -> {
            String at = "dimension " + d.id();
            if (!TYPES.contains(d.type())) out.add(at + ": type must be one of " + TYPES);
            if (d.grain() != null && (!GRAINS.contains(d.grain()) || !"date".equals(d.type())))
                out.add(at + ": a time dimension has type date and grain month, quarter or year");
            if (d.tones() != null) d.tones().forEach((v, t) -> {
                if (!TONES.contains(t)) out.add(at + ": tone '" + t + "' for '" + v + "' is not one of " + TONES);
                if (d.values() != null && !d.values().contains(v)) out.add(at + ": tone for '" + v + "', which is not one of its values");
            });
        });
        Set<String> metricIds = new HashSet<>();
        for (Model m : sm.models().values()) checkModel(sm, m, metricIds, out);
        long entities = sm.models().values().stream().filter(Model::entityModel).count();
        if (entities > 1) out.add("only one model can be the entity model; found " + entities);
        for (DerivedMetric d : sm.derived().values()) {
            if (!metricIds.add(d.id())) out.add("metric id '" + d.id() + "' is used twice (ids are shared by measures and derived metrics)");
            checkDerived(sm, d, out);
        }
        checkCycles(sm, out);
        return out;
    }

    private static void checkModel(SemanticModel sm, Model m, Set<String> metricIds, List<String> out) {
        String at = "model " + m.id();
        if (m.node() == null || !IDENT.matcher(m.node()).matches()) out.add(at + ": node (the label it reads) is required");
        if (m.time() != null && !GRAINS.contains(m.time().grain())) out.add(at + ": time grain must be month, quarter or year");
        if (m.entity() != null && !m.entityModel() && m.entityJoin() == null)
            out.add(at + ": a client-owned model needs entityJoin (the relationship to the entity model's node)");
        m.joins().forEach((name, j) -> {
            if (sm.dimension(name) != null && !sm.dimension(name).id().equals(name)) out.add(at + ": join '" + name + "' clashes with a dimension");
            if (!IDENT.matcher(name).matches() || name.equals("n")) out.add(at + ": join name '" + name + "' must be a plain identifier other than n");
            if (j.hops().isEmpty()) out.add(at + ": join '" + name + "' needs rel, dir and node, or a path of them");
            for (Model.Hop h : j.hops())
                if (h.rel() == null || !IDENT.matcher(h.rel()).matches() || h.node() == null || !IDENT.matcher(h.node()).matches()
                        || !Set.of("in", "out").contains(h.dir()))
                    out.add(at + ": join '" + name + "' needs rel, dir (in or out) and node on every hop");
        });
        m.dimensions().forEach((dim, b) -> {
            Dimension d = sm.dimension(dim);
            if (d == null) out.add(at + ": binds unknown dimension '" + dim + "'");
            else if (d.isTime()) out.add(at + ": time dimension '" + dim + "' comes from the model's time field; do not bind it");
            else if (d.displayColumn() != null && b.display() == null) out.add(at + ": dimension '" + dim + "' is keyed; give its display path");
            checkPath(m, b.field(), at + " dimension " + dim, out);
            if (b.display() != null) checkPath(m, b.display(), at + " dimension " + dim + " display", out);
        });
        m.segments().forEach((name, s) -> s.where().forEach(c -> {
            if (!Condition.OPS.contains(c.op())) out.add(at + " segment " + name + ": op '" + c.op() + "' is not one of " + Condition.OPS);
            if (c.value() == null) out.add(at + " segment " + name + ": condition on " + c.field() + " needs a value");
            if ((c.op().equals("in") || c.op().equals("nin")) && (c.value() == null || !c.value().isArray()))
                out.add(at + " segment " + name + ": " + c.op() + " needs a list value");
        }));
        for (Model.Measure ms : m.measures()) {
            String mat = at + " measure " + ms.id();
            if (!metricIds.add(ms.id())) out.add("metric id '" + ms.id() + "' is used twice (ids are shared by measures and derived metrics)");
            if (!AGGS.contains(ms.agg())) out.add(mat + ": agg must be one of " + AGGS);
            boolean valued = ms.field() != null || ms.expr() != null;
            if (ms.agg().equals("count") && valued) out.add(mat + ": count takes neither field nor expr");
            if (!ms.agg().equals("count") && !valued) out.add(mat + ": " + ms.agg() + " needs a field or an expr");
            if (ms.field() != null && ms.expr() != null) out.add(mat + ": give a field or an expr, not both");
            if (ms.expr() != null && ms.sources().isEmpty()) out.add(mat + ": an expr needs sources (the catalog fields it reads)");
            if (ms.expr() != null) checkExpr(m, ms.expr(), ms.sources(), mat, out);
            if (!UNITS.contains(ms.unit())) out.add(mat + ": unit '" + ms.unit() + "' is not one of " + UNITS);
            if (!POLARITIES.contains(ms.polarity())) out.add(mat + ": polarity must be one of " + POLARITIES);
            if (ms.description().isBlank()) out.add(mat + ": needs a description (it is shown to bankers as the figure's definition)");
            ms.segments().forEach(s -> { if (!m.segments().containsKey(s)) out.add(mat + ": unknown segment '" + s + "'"); });
            if (ms.field() != null) checkPath(m, ms.field(), mat, out);
        }
        if (m.records() != null) {
            Set<String> ids = new HashSet<>();
            for (Model.RecordField f : m.records().fields()) {
                String fat = at + " record field " + f.id();
                if (!ids.add(f.id())) out.add(fat + ": defined twice");
                if (!TYPES.contains(f.type())) out.add(fat + ": type must be one of " + TYPES);
                if ((f.field() == null) == (f.expr() == null)) out.add(fat + ": give exactly one of field or expr");
                if (f.expr() != null) checkExpr(m, f.expr(), f.sources(), fat, out);
                if (f.unit() != null && !UNITS.contains(f.unit())) out.add(fat + ": unit '" + f.unit() + "' is not one of " + UNITS);
                if (f.dimension() != null && sm.dimension(f.dimension()) == null) out.add(fat + ": unknown dimension '" + f.dimension() + "'");
                if (f.field() != null) checkPath(m, f.field(), fat, out);
                if (f.unitFrom() != null) {
                    if (m.records().fields().stream().noneMatch(o -> f.unitFrom().field().equals(o.field())))
                        out.add(fat + ": unitFrom field '" + f.unitFrom().field() + "' must also be a record field");
                    f.unitFrom().map().values().forEach(u -> { if (!UNITS.contains(u)) out.add(fat + ": unitFrom maps to unknown unit '" + u + "'"); });
                }
            }
            m.records().defaultSort().forEach(s -> { if (!ids.contains(s.by())) out.add(at + ": records defaultSort by unknown field '" + s.by() + "'"); });
            if (m.records().fields().isEmpty()) out.add(at + ": records needs fields");
        }
    }

    /**
     * An expression is a Cypher expression over the model's node {@code n} and its joins: text, no clauses or
     * parameters, and every property it reads ({@code n.x}, {@code join.x}) declared in its sources ({@code Label.x}),
     * so lineage is never wrong.
     */
    static void checkExpr(Model m, JsonNode expr, List<String> sources, String at, List<String> out) {
        if (!expr.isTextual() || expr.asText().isBlank()) { out.add(at + ": expr must be a Cypher expression over n (text)"); return; }
        String text = expr.asText();
        List<String> problems = ReadOnlyCypher.problems("RETURN " + text);
        if (!problems.isEmpty()) out.add(at + ": expr " + String.join("; ", problems));
        String code = ReadOnlyCypher.code(text);
        if (code.contains("$")) out.add(at + ": expr cannot use parameters");
        if (code.matches("(?is).*\\b(MATCH|RETURN|UNWIND|CALL)\\b.*")) out.add(at + ": expr is one expression, not a query");
        Matcher r = PROPERTY_REF.matcher(code);
        while (r.find()) {
            String label = r.group(1).equals("n") ? m.node() : m.joins().containsKey(r.group(1)) ? m.joins().get(r.group(1)).node() : null;
            if (label == null) { out.add(at + ": expr reads '" + r.group(1) + "." + r.group(2) + "'; an expr reads only the model's node n and its joins"); continue; }
            if (!sources.contains(label + "." + r.group(2)))
                out.add(at + ": expr reads " + r.group(1) + "." + r.group(2) + ", which is not in its sources (" + label + "." + r.group(2) + ")");
        }
    }

    private static void checkPath(Model m, String path, String at, List<String> out) {
        if (path == null || path.isBlank()) { out.add(at + ": empty field path"); return; }
        int dot = path.indexOf('.');
        if (dot > 0 && !m.joins().containsKey(path.substring(0, dot)))
            out.add(at + ": path '" + path + "' goes through '" + path.substring(0, dot) + "', which is not a join of the model");
    }

    private static void checkDerived(SemanticModel sm, DerivedMetric d, List<String> out) {
        String at = "metric " + d.id();
        if (!KINDS.contains(d.kind())) { out.add(at + ": kind must be one of " + KINDS); return; }
        switch (d.kind()) {
            case "ratio" -> { if (d.numerator() == null || d.denominator() == null) out.add(at + ": ratio needs numerator and denominator"); }
            case "difference" -> { if (d.left() == null || d.right() == null) out.add(at + ": difference needs left and right"); }
            case "change" -> {
                if (d.of() == null) out.add(at + ": change needs of");
                if (d.compare() == null || !Window.COMPARES.contains(d.compare())) out.add(at + ": change needs compare priorYear or priorPeriod");
            }
            case "same" -> { if (d.of() == null) out.add(at + ": same needs of"); }
            default -> { }
        }
        if (!UNITS.contains(d.unit())) out.add(at + ": unit '" + d.unit() + "' is not one of " + UNITS);
        if (!POLARITIES.contains(d.polarity())) out.add(at + ": polarity must be one of " + POLARITIES);
        if (d.description().isBlank()) out.add(at + ": needs a description");
        for (DerivedMetric.Operand o : d.operands()) checkOperand(sm, o, at, out);
        if (d.kind().equals("difference") && d.left() != null && d.right() != null) {
            String l = unitOf(sm, d.left()), r = unitOf(sm, d.right());
            if (l != null && r != null && !l.equals(r)) out.add(at + ": difference of " + l + " and " + r);
        }
    }

    private static void checkOperand(SemanticModel sm, DerivedMetric.Operand o, String at, List<String> out) {
        if (o instanceof DerivedMetric.Sum s) { s.of().forEach(p -> checkOperand(sm, p, at, out)); return; }
        DerivedMetric.Ref r = (DerivedMetric.Ref) o;
        if (!sm.isMetric(r.metric())) out.add(at + ": refers to unknown metric '" + r.metric() + "'");
        if (r.window() != null) r.window().problems().forEach(p -> out.add(at + ": operand " + r.metric() + ": " + p));
    }

    static String unitOf(SemanticModel sm, DerivedMetric.Operand o) {
        if (o instanceof DerivedMetric.Sum s) return s.of().isEmpty() ? null : unitOf(sm, s.of().get(0));
        String id = ((DerivedMetric.Ref) o).metric();
        if (sm.measure(id) != null) return sm.measure(id).measure().unit();
        return sm.derivedMetric(id) == null ? null : sm.derivedMetric(id).unit();
    }

    private static void checkCycles(SemanticModel sm, List<String> out) {
        Map<String, Integer> state = new HashMap<>();
        for (String id : sm.derived().keySet()) visit(sm, id, state, new ArrayDeque<>(), out);
    }

    private static void visit(SemanticModel sm, String id, Map<String, Integer> state, Deque<String> path, List<String> out) {
        DerivedMetric d = sm.derivedMetric(id);
        if (d == null) return;
        Integer s = state.get(id);
        if (s != null && s == 2) return;
        if (s != null && s == 1) { out.add("metric cycle: " + String.join(" -> ", path) + " -> " + id); return; }
        state.put(id, 1);
        path.addLast(id);
        for (String ref : refs(d)) visit(sm, ref, state, path, out);
        path.removeLast();
        state.put(id, 2);
    }

    static List<String> refs(DerivedMetric d) {
        List<String> out = new ArrayList<>();
        d.operands().forEach(o -> collect(o, out));
        return out;
    }

    private static void collect(DerivedMetric.Operand o, List<String> out) {
        if (o instanceof DerivedMetric.Sum s) s.of().forEach(p -> collect(p, out));
        else out.add(((DerivedMetric.Ref) o).metric());
    }

    // ------------------------------------------------------------------ against the data dictionary
    /** Problems found comparing the model with the data dictionary (the {@code (:_Catalog)} entries, one per label). */
    public static List<String> againstCatalog(SemanticModel sm, JsonNode catalog) {
        Map<String, JsonNode> entries = new HashMap<>();
        catalog.forEach(e -> entries.put(e.path("_id").asText(), e));
        List<String> out = new ArrayList<>();
        for (Model m : sm.models().values()) {
            String at = "model " + m.id();
            JsonNode entry = entries.get(m.node());
            if (entry == null) { out.add(at + ": label '" + m.node() + "' is not in the data dictionary"); continue; }
            m.joins().forEach((name, j) -> {
                // each hop must be many-to-one from where it starts; the dictionary records a relationship on the label it
                // is many-to-one from (a hop the other way round reads the other label's entry, reversed)
                String from = m.node();
                for (Model.Hop h : j.hops()) {
                    if (!entries.containsKey(h.node())) { out.add(at + " join " + name + ": label '" + h.node() + "' is not in the data dictionary"); return; }
                    String pattern = "in".equals(h.dir()) ? "(:" + h.node() + ")-[:" + h.rel() + "]->(:" + from + ")" : "(:" + from + ")-[:" + h.rel() + "]->(:" + h.node() + ")";
                    JsonNode rel = null;
                    for (JsonNode r : entries.get(from).path("relationships"))
                        if (r.path("type").asText().equals(h.rel()) && r.path("with").asText().equals(h.node()) && r.path("direction").asText().equals(h.dir())) rel = r;
                    if (rel == null) out.add(at + " join " + name + ": the dictionary has no relationship " + pattern);
                    else if (!"many-to-one".equals(rel.path("cardinality").asText()))
                        out.add(at + " join " + name + ": " + pattern + " is " + rel.path("cardinality").asText() + ", so the join could multiply rows");
                    from = h.node();
                }
            });
            if (m.entity() != null) field(entries, m, m.entity(), at + " entity", out);
            if (m.time() != null) field(entries, m, m.time().field(), at + " time", out);
            m.dates().forEach((n, f) -> field(entries, m, f, at + " date " + n, out));
            m.dimensions().forEach((dim, b) -> {
                JsonNode f = field(entries, m, b.field(), at + " dimension " + dim, out);
                if (b.display() != null) field(entries, m, b.display(), at + " dimension " + dim + " display", out);
                Dimension d = sm.dimension(dim);
                if (f != null && d != null && d.values() != null && f.has("allowedValues")) {
                    Set<String> catalogValues = new LinkedHashSet<>(SemanticModel.strings(f.get("allowedValues")));
                    if (!catalogValues.equals(new LinkedHashSet<>(d.values())))
                        out.add(at + " dimension " + dim + ": values " + d.values() + " differ from the dictionary's " + catalogValues);
                }
            });
            m.segments().forEach((name, s) -> s.where().forEach(c -> {
                JsonNode f = field(entries, m, c.field(), at + " segment " + name, out);
                checkValues(f, c, at + " segment " + name, out);
            }));
            for (Model.Measure ms : m.measures()) {
                if (ms.field() != null) field(entries, m, ms.field(), at + " measure " + ms.id(), out);
                for (String src : ms.sources()) source(entries, src, at + " measure " + ms.id(), out);
            }
            if (m.records() != null) for (Model.RecordField f : m.records().fields()) {
                if (f.field() != null) field(entries, m, f.field(), at + " record field " + f.id(), out);
                for (String src : f.sources()) source(entries, src, at + " record field " + f.id(), out);
            }
        }
        return out;
    }

    /** The dictionary entry for a model path (a property, or join.property through a join); null (and a problem) if absent. */
    private static JsonNode field(Map<String, JsonNode> entries, Model m, String path, String at, List<String> out) {
        String label = m.node();
        String name = path;
        int dot = path.indexOf('.');
        if (dot > 0 && m.joins().containsKey(path.substring(0, dot))) {
            label = m.joins().get(path.substring(0, dot)).node();
            name = path.substring(dot + 1);
        }
        JsonNode entry = entries.get(label);
        if (entry == null) { out.add(at + ": label '" + label + "' is not in the data dictionary"); return null; }
        for (JsonNode f : entry.path("properties")) if (f.path("name").asText().equals(name)) return f;
        out.add(at + ": property '" + name + "' is not in the dictionary entry for " + label);
        return null;
    }

    private static void source(Map<String, JsonNode> entries, String ref, String at, List<String> out) {
        int dot = ref.indexOf('.');
        if (dot < 0) { out.add(at + ": source '" + ref + "' must be Label.property"); return; }
        JsonNode entry = entries.get(ref.substring(0, dot));
        String name = ref.substring(dot + 1);
        if (entry == null) { out.add(at + ": source label of '" + ref + "' is not in the data dictionary"); return; }
        for (JsonNode f : entry.path("properties")) if (f.path("name").asText().equals(name)) return;
        out.add(at + ": source '" + ref + "' is not in the data dictionary");
    }

    private static void checkValues(JsonNode field, Condition c, String at, List<String> out) {
        if (field == null || !field.has("allowedValues") || !Set.of("eq", "ne", "in", "nin").contains(c.op())) return;
        Set<String> allowed = new HashSet<>(SemanticModel.strings(field.get("allowedValues")));
        c.textValues().stream().filter(v -> !allowed.contains(v)).forEach(v ->
                out.add(at + ": value '" + v + "' of " + c.field() + " is not an allowed value " + allowed));
    }
}
