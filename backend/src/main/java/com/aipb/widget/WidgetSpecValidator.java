package com.aipb.widget;

import com.aipb.model.Layout;
import com.aipb.semantic.ResultSet;
import com.aipb.semantic.SemanticQueryException;
import com.aipb.service.LayoutService;
import com.aipb.source.GraphMcpSource;
import com.aipb.source.ReadOnlyCypher;
import com.aipb.source.SourceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Checks a widget spec before it can be saved, in two passes. Static: the schema; for a query, that the semantic layer
 * can answer it (metrics, dimensions, windows, filters) and the column overrides; for custom data, that it is Cypher
 * (not a MongoDB pipeline to rebuild), read-only, free of the wall clock, over labels the graph has, with every
 * parameter declared, and units vs formats; then, for both, the primitive's roles against the columns
 * the widget will have, and whether the target page can supply what it needs. Live (only when static passed): the
 * data is run with sample parameters and its real rows are checked against those columns, the primitive's row limits
 * and unit sanity. Every spec format is accepted (read through {@link SpecMigrator}). Messages say what to change.
 */
@Service
@RequiredArgsConstructor
public class WidgetSpecValidator {

    static final int PREVIEW_ROWS = 50;
    /** The wall clock in Cypher: date(), datetime(), timestamp() ... with no argument, and their realtime/statement/transaction forms. */
    private static final java.util.regex.Pattern WALL_CLOCK = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w.$])(?:(?:date|datetime|localdatetime|localtime|time)(?:\\s*\\.\\s*(?:realtime|statement|transaction))?\\s*\\(\\s*\\)|timestamp\\s*\\(\\s*\\))");
    private static final Set<String> VIEW_KEYS = Set.of("value", "compare", "secondary", "x", "y", "orientation", "stacked",
            "columns", "title", "subtitle", "badge", "meta", "reference", "emphasis", "polarity", "headline", "warnAt", "series",
            "statements", "figures");
    static final String NARRATIVE = "narrative";
    private static final Set<String> ADDITIVE_UNITS = Set.of("USD", "count");
    private static final String EMPTY_HINT = "Common causes: comparing a date property to plain text (wrap it: datetime($asOfDate)), "
            + "a filter value that does not exist (check allowedValues in the data dictionary), a relationship followed the wrong "
            + "way, or a property name typo.";

    private final WidgetPrimitives primitives;
    private final WidgetDataService data;
    private final GraphMcpSource source;
    private final LayoutService layouts;
    private final ObjectMapper mapper;

    /**
     * Validates a spec. {@code pageId} (optional) is the page it is meant for: its parameters must cover the spec's,
     * and its first choices serve as sample values. {@code params} gives explicit sample values.
     */
    public ValidationReport validate(JsonNode specIn, String layoutId, String pageId, Map<String, String> params) {
        Issues is = new Issues();
        JsonNode specJson = SpecMigrator.migrate(specIn);
        List<String> schemaErrors = primitives.schemaErrors(specJson);
        if (!schemaErrors.isEmpty()) {
            schemaErrors.forEach(m -> is.error("schema", pathOf(m), m));
            return is.report(null, null);
        }
        WidgetSpec spec = mapper.convertValue(specJson, WidgetSpec.class);
        Layout.Page page = pageId == null ? null : page(layoutId, pageId, is);

        List<ResultSet.Column> columns = spec.isQuery() ? checkQuery(spec, is) : checkCustom(spec, is);
        if (columns != null) checkView(spec, byName(columns), is);
        if (page != null) checkPage(spec, page, params, is);
        if (is.hasErrors()) return is.report(null, null);

        Map<String, String> sample = sampleParams(spec, page, params, is);
        if (is.hasErrors()) return is.report(sample, null);
        return live(spec, sample, is);
    }

    /** The semantic layer must be able to answer the query; overrides must name its columns. Returns the widget's columns. */
    private List<ResultSet.Column> checkQuery(WidgetSpec spec, Issues is) {
        if (!spec.paramsOrEmpty().isEmpty())
            is.error("params", "params", "a query spec takes the client and dates from the page; remove params (they are for custom Cypher)");
        List<ResultSet.Column> cols;
        try {
            cols = data.columns(spec);
        } catch (SemanticQueryException e) {
            e.issues().forEach(i -> is.error("query", "data.query" + (i.path().isEmpty() ? "" : "." + i.path()), i.message()));
            return null;
        }
        Map<String, ResultSet.Column> byName = byName(cols);
        for (WidgetSpec.Column o : spec.data().overridesOrEmpty()) {
            String path = "data.columns." + o.name();
            ResultSet.Column c = byName.get(o.name());
            if (c == null) { is.error("columns", path, "override names '" + o.name() + "', which the query does not return; it returns: "
                    + String.join(", ", byName.keySet())); continue; }
            if (o.type() != null || o.unit() != null || o.format() != null)
                is.error("columns", path, "a query's columns get their type, unit and format from the semantic layer; an override sets only label and tones");
            if (o.tones() != null && !"string".equals(c.type())) is.error("columns", path, "tones make a badge, which needs a text column; '"
                    + o.name() + "' is " + c.type());
        }
        return cols;
    }

    /** The escape hatch's own rules: Cypher (not a pipeline to rebuild), read-only, labels, parameters, declared columns. */
    private List<ResultSet.Column> checkCustom(WidgetSpec spec, Issues is) {
        if (spec.custom().needsRebuild()) {
            is.error("needs_rebuild", "data.custom", "this custom widget was written for MongoDB (collection '" + spec.custom().collection()
                    + "' and an aggregation pipeline); the bank data is now a graph, so rewrite it as read-only Cypher in data.custom.cypher"
                    + " - or, better, as a semantic query (data.query)");
            return null;
        }
        String cypher = spec.custom().cypher();
        ReadOnlyCypher.problems(cypher).forEach(p -> is.error("read_only", "data.custom.cypher", p));
        java.util.regex.Matcher clock = WALL_CLOCK.matcher(ReadOnlyCypher.code(cypher));
        if (clock.find())
            is.error("wall_clock", "data.custom.cypher", clock.group().replaceAll("\\s+", "") + " is the wall clock; the data has its own 'today' - "
                    + "use datetime($asOfDate) (built in) instead");
        checkLabels(spec, is);
        checkParams(spec, is);
        checkColumns(spec, is);
        return data.columns(spec);
    }

    // ------------------------------------------------------------------ static
    private void checkLabels(WidgetSpec spec, Issues is) {
        List<String> used = WidgetDataService.labels(spec.custom().cypher());
        if (used.isEmpty()) {
            is.error("labels", "data.custom.cypher", "the query matches no label; start from one, e.g. MATCH (h:Holding) WHERE h.clientId = $clientId");
            return;
        }
        for (String l : used) if (l.startsWith("_"))
            is.error("labels", "data.custom.cypher", "'" + l + "' is metadata, not data; widgets read data labels");
        try {
            List<String> all = source.labelsCached();
            used.stream().filter(l -> !l.startsWith("_") && !all.contains(l)).forEach(l ->
                    is.error("labels", "data.custom.cypher", "no label '" + l + "' in the graph; labels: " + String.join(", ", all)));
        } catch (SourceException e) {
            is.error("source_unavailable", "data.custom.cypher", "cannot reach the source data to check the labels: " + e.getMessage());
        }
    }

    private void checkParams(WidgetSpec spec, Issues is) {
        Set<String> declared = new LinkedHashSet<>();
        for (WidgetSpec.Param p : spec.paramsOrEmpty()) {
            if (!declared.add(p.name())) is.error("params", "params", "parameter '" + p.name() + "' is declared twice");
            if (ParamBinder.AS_OF_DATE.equals(p.name()) && !"date".equals(p.type()))
                is.error("params", "params", "asOfDate is built in and must have type date");
        }
        Set<String> used = ParamBinder.referenced(spec.custom().cypher());
        used.stream().filter(u -> !declared.contains(u) && !ParamBinder.AS_OF_DATE.equals(u)).forEach(u ->
                is.error("params", "data.custom.cypher", "the query uses $" + u + " but '" + u + "' is not declared in params"));
        declared.stream().filter(d -> !used.contains(d)).forEach(d ->
                is.warn("params", "params", "parameter '" + d + "' is declared but never used in the query"));
    }

    private void checkColumns(WidgetSpec spec, Issues is) {
        Set<String> names = new HashSet<>();
        for (WidgetSpec.Column c : spec.custom().columns()) {
            String path = "data.custom.columns." + c.name();
            if (!names.add(c.name())) is.error("columns", path, "column '" + c.name() + "' is declared twice");
            String format = c.formatOrDefault();
            List<String> unitFormats = primitives.formatsForUnit(c.unitOrDefault());
            if (!unitFormats.contains(format))
                is.error("unit_format", path, "column '" + c.name() + "' has unit " + c.unitOrDefault() + " so its format must be "
                        + String.join(" or ", unitFormats) + ", not " + format + unitHint(c.unitOrDefault()));
            List<String> formatTypes = primitives.typesForFormat(format);
            if (!formatTypes.contains(c.type()))
                is.error("format_type", path, "format " + format + " displays " + String.join("/", formatTypes)
                        + " values but column '" + c.name() + "' has type " + c.type());
            if (c.tones() != null && !"badge".equals(format))
                is.warn("columns", path, "tones only apply to format badge; column '" + c.name() + "' has format " + format);
        }
    }

    private void checkView(WidgetSpec spec, Map<String, ResultSet.Column> cols, Issues is) {
        WidgetSpec.View v = spec.view();
        JsonNode prim = primitives.primitive(v.primitive());
        JsonNode roles = prim.path("roles");
        JsonNode options = prim.path("options");
        JsonNode viewJson = mapper.valueToTree(v);
        Set<String> allowed = new LinkedHashSet<>();
        roles.fieldNames().forEachRemaining(allowed::add);
        options.fieldNames().forEachRemaining(allowed::add);
        if (NARRATIVE.equals(v.primitive())) allowed.add("statements");

        viewJson.fieldNames().forEachRemaining(k -> {
            if (!k.equals("primitive") && VIEW_KEYS.contains(k) && !allowed.contains(k))
                is.error("view_role", "view." + k, v.primitive() + " has no role '" + k + "'; it uses " + String.join(", ", allowed));
        });
        roles.fields().forEachRemaining(e -> {
            String role = e.getKey();
            JsonNode def = e.getValue();
            JsonNode set = viewJson.get(role);
            if (set == null || set.isNull()) {
                if (def.path("required").asBoolean(false))
                    is.error("view_role", "view." + role, v.primitive() + " needs view." + role
                            + (def.path("many").asBoolean(false) ? " (a list of column names)" : " (a column name)"));
                return;
            }
            List<String> names = set.isArray() ? WidgetPrimitives.strings(set) : List.of(set.asText());
            List<String> types = WidgetPrimitives.strings(def.path("types"));
            for (String col : names) {
                ResultSet.Column c = cols.get(col);
                if (c == null) {
                    is.error("view_role", "view." + role, "view." + role + " refers to '" + col + "', which is not a column; columns: "
                            + String.join(", ", cols.keySet()));
                } else if (!types.contains(c.type())) {
                    is.error("view_role", "view." + role, v.primitive() + " " + role + " takes " + String.join("/", types)
                            + " columns but '" + col + "' has type " + c.type());
                }
            }
        });
        options.fields().forEachRemaining(e -> {
            JsonNode set = viewJson.get(e.getKey());
            if (set != null && !set.isNull() && e.getValue().isArray() && !WidgetPrimitives.strings(e.getValue()).contains(set.asText()))
                is.error("view_option", "view." + e.getKey(), v.primitive() + " " + e.getKey() + " is one of "
                        + String.join(", ", WidgetPrimitives.strings(e.getValue())) + "; got '" + set.asText() + "'");
        });
        if (v.y() != null && v.y().size() > 1) {
            Set<String> units = new LinkedHashSet<>();
            v.y().forEach(y -> { ResultSet.Column c = cols.get(y); if (c != null) units.add(c.unitOrDefault()); });
            if (units.size() > 1) is.warn("view_role", "view.y", "the y series mix units " + units + "; they share one axis");
        }
        if (v.series() != null && v.y() != null && v.y().size() != 1)
            is.error("view_role", "view.series", "with series, give exactly one y column (its values are split by " + v.series() + ")");
        if (NARRATIVE.equals(v.primitive()))
            NarrativeRenderer.problems(v, cols, formats()).forEach(p -> is.error("narrative", p.path(), p.message()));
        checkIntents(spec, cols, is);
    }

    /** Design intents that need more than a role check: what a reference, headline, meter or composition can mean. */
    private void checkIntents(WidgetSpec spec, Map<String, ResultSet.Column> cols, Issues is) {
        WidgetSpec.View v = spec.view();
        String measured = v.value() != null ? v.value() : v.y() != null && !v.y().isEmpty() ? v.y().get(0) : null;
        ResultSet.Column m = measured == null ? null : cols.get(measured);
        ResultSet.Column ref = v.reference() == null ? null : cols.get(v.reference());
        if (m != null && ref != null && !m.unitOrDefault().equals(ref.unitOrDefault()))
            is.error("view_intent", "view.reference", "reference '" + ref.name() + "' has unit " + ref.unitOrDefault() + " but '" + m.name()
                    + "' has unit " + m.unitOrDefault() + "; a reference is drawn on the same scale as the values");
        if (m != null && "total".equals(v.headline()) && !ADDITIVE_UNITS.contains(m.unitOrDefault()))
            is.error("view_intent", "view.headline", "headline total adds up '" + m.name() + "', but unit " + m.unitOrDefault()
                    + " does not add up; use latest, average or change");
        switch (v.primitive()) {
            case "meter" -> {
                if (m != null && ref == null && !m.unitOrDefault().equals("ratio"))
                    is.error("view_intent", "view.reference", "meter value '" + m.name() + "' has unit " + m.unitOrDefault()
                            + "; give the maximum as view.reference, or use a 0-1 ratio");
            }
            case "composition" -> {
                if (v.y() != null && v.y().size() != 1)
                    is.error("view_role", "view.y", "composition shows exactly one column (the size of each part); got " + v.y().size());
                else if (m != null && !ADDITIVE_UNITS.contains(m.unitOrDefault()))
                    is.error("view_intent", "view.y", "composition splits a total into parts, so '" + m.name() + "' must add up (unit USD or count); got "
                            + m.unitOrDefault());
            }
            default -> { }
        }
        if (v.warnAt() != null && !v.primitive().equals("meter"))
            is.error("view_option", "view.warnAt", "warnAt only applies to meter");
    }

    private void checkPage(WidgetSpec spec, Layout.Page page, Map<String, String> params, Issues is) {
        for (String p : data.requiredParams(spec)) {
            if (primitives.builtinParams().contains(p) || page.param(p) != null) continue;
            if (params != null && params.containsKey(p)) continue;
            is.error("page_param", "params", "page '" + page.getTitle() + "' does not supply '" + p + "' (it supplies: "
                    + (page.getParams() == null || page.getParams().isEmpty() ? "nothing" : String.join(", ",
                    page.getParams().stream().map(Layout.PageParam::getName).toList()))
                    + "); place it on a page that does, or give it a fixed value");
        }
    }

    /** Sample values to run with: given ones, else the page's first choice. Required ones must resolve; optional ones may not. */
    private Map<String, String> sampleParams(WidgetSpec spec, Layout.Page page, Map<String, String> given, Issues is) {
        Map<String, String> sample = new LinkedHashMap<>();
        List<String> names = spec.isQuery() ? data.usableParams(spec) : spec.paramsOrEmpty().stream().map(WidgetSpec.Param::name).toList();
        List<String> required = data.requiredParams(spec);
        for (String name : names) {
            String v = given == null ? null : given.get(name);
            if ((v == null || v.isBlank()) && page != null && page.param(name) != null) {
                try {
                    List<Map<String, String>> opts = data.options(page.param(name));
                    if (!opts.isEmpty()) v = opts.get(0).get("value");
                } catch (SourceException e) {
                    is.error("source_unavailable", "params", "cannot read choices for '" + name + "': " + e.getMessage());
                }
            }
            if (v != null && !v.isBlank()) sample.put(name, v);
            else if (required.contains(name) && !primitives.builtinParams().contains(name))
                is.error("sample_param", "params", "give a sample value for '" + name + "' to run the preview");
        }
        // a query that follows the page's period is previewed with the page's default period
        if (spec.isQuery() && spec.data().query().followsPagePeriod()) {
            String asked = given == null ? null : given.get("period");
            Layout.PeriodSlot slot = page == null || page.getContext() == null ? null : page.getContext().getPeriod();
            Layout.PeriodOption o = slot == null ? null : slot.option(asked != null ? asked : slot.getDefaultOption());
            if (o != null) sample.put("period", o.getWindow());
            else if (asked != null) sample.put("period", asked);
        }
        return sample;
    }

    // ------------------------------------------------------------------ live
    private ValidationReport live(WidgetSpec spec, Map<String, String> sample, Issues is) {
        WidgetDataService.DataResult r;
        try {
            r = data.execute(spec, sample);
        } catch (IllegalArgumentException e) {
            is.error("params", "params", e.getMessage());
            return is.report(sample, null);
        } catch (SemanticQueryException e) {
            e.issues().forEach(i -> is.error("query", "data.query" + (i.path().isEmpty() ? "" : "." + i.path()), i.message()));
            return is.report(sample, null);
        } catch (SourceException e) {
            is.error("query", spec.isQuery() ? "data.query" : "data.custom.cypher", "the query failed: " + e.getMessage());
            return is.report(sample, null);
        }
        JsonNode rows = r.rows();
        JsonNode prim = primitives.primitive(spec.view().primitive());
        JsonNode limits = spec.view().x() != null && prim.has("rowsWithX") ? prim.path("rowsWithX") : prim.path("rows");
        String where = " for " + (sample.isEmpty() ? "the sample run" : sample);
        String dataPath = spec.isQuery() ? "data.query" : "data.custom.cypher";
        // with a series role, the rows are periods x series: the primitive's limits apply to the periods (or categories)
        int count = spec.view().series() == null ? r.count() : distinct(rows, spec.view().x());

        if (r.truncated())
            is.error("truncated", dataPath, "the query produced " + (r.total() == null ? "too many" : r.total())
                    + " rows and was cut off at " + r.count() + "; " + (spec.isQuery() ? "add a filter, a coarser dimension or a limit" : "aggregate, filter or LIMIT in the query"));
        if (r.count() == 0) {
            if (limits.path("min").asInt(0) > 0) is.error("empty", dataPath, "the query returned no rows" + where + ". " + EMPTY_HINT);
            else is.warn("empty", dataPath, "the query returned no rows" + where + "; fine if that can happen, otherwise: " + EMPTY_HINT);
        } else {
            if (count < limits.path("min").asInt(0))
                is.error("rows", "view.primitive", spec.view().primitive() + " needs at least " + limits.path("min").asInt() + " rows; got " + count);
            if (limits.has("max") && count > limits.path("max").asInt())
                is.error("rows", "view.primitive", spec.view().primitive() + " shows at most " + limits.path("max").asInt() + " rows; got "
                        + count + (spec.view().primitive().equals("metric") ? " - return one row (aggregate with no grouping key)" : " - aggregate or LIMIT"));
            else if (limits.has("warn") && count > limits.path("warn").asInt())
                is.warn("rows", "view.primitive", count + " rows is a lot for a " + spec.view().primitive() + "; consider grouping or a top-N");
            if (spec.view().series() != null && distinct(rows, spec.view().series()) > 4)
                is.error("rows", "view.series", "a chart shows at most 4 series; '" + spec.view().series() + "' has " + distinct(rows, spec.view().series())
                        + " values - filter, or use a table");
            checkRows(spec, r.columns(), rows, is);
        }
        NarrativeRenderer.Narrative narrative = null;
        if (NARRATIVE.equals(spec.view().primitive()) && r.count() > 0) {
            narrative = NarrativeRenderer.render(spec.view(), r.columns(), rows);
            if (narrative.lines().isEmpty())
                is.error("narrative", "view.statements", "no statement shows" + where + ": give the headline no condition, and check the slots have values");
            else if (narrative.skipped() > 0)
                is.warn("narrative", "view.statements", narrative.skipped() + " statement(s) were left out" + where + " because a slot had no value");
        }
        r.notes().forEach(n -> is.warn("note", dataPath, n));
        ValidationReport.Preview preview = new ValidationReport.Preview(r.columns(),
                WidgetDataService.project(rows, r.columns(), PREVIEW_ROWS), r.count(), r.total(), r.truncated(), r.asOfDate(), r.notes(), narrative);
        return is.report(sample, preview);
    }

    private Set<String> formats() {
        Set<String> out = new LinkedHashSet<>();
        primitives.catalog().path("formats").fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static int distinct(JsonNode rows, String column) {
        Set<String> seen = new HashSet<>();
        rows.forEach(row -> seen.add(row.path(column).asText()));
        return seen.size();
    }

    private void checkRows(WidgetSpec spec, List<ResultSet.Column> columns, JsonNode rows, Issues is) {
        Set<String> keys = new LinkedHashSet<>();
        rows.forEach(row -> row.fieldNames().forEachRemaining(keys::add));
        Set<String> declared = new LinkedHashSet<>();
        String colPath = spec.isQuery() ? "data.query" : "data.custom.columns.";
        for (ResultSet.Column c : columns) {
            declared.add(c.name());
            String path = spec.isQuery() ? colPath : colPath + c.name();
            if (!keys.contains(c.name())) {
                is.error("missing_column", path, "column '" + c.name() + "' is declared but no result row has it; rows have: " + String.join(", ", keys));
                continue;
            }
            List<JsonNode> values = new ArrayList<>();
            rows.forEach(row -> { JsonNode v = row.get(c.name()); if (v != null && !v.isNull()) values.add(v); });
            if (values.isEmpty()) { is.warn("all_null", path, "column '" + c.name() + "' is null in every row"); continue; }
            values.stream().filter(v -> !hasType(v, c.type())).findFirst().ifPresent(bad ->
                    is.error("type", path, "column '" + c.name() + "' is declared " + c.type() + " but a row has " + bad
                            + (c.type().equals("date") ? " (a date must come back as a BSON date, not a string or number)" : "")));
            if (c.type().equals("number") && c.unitFrom() == null) checkScale(c, values, path, is);
        }
        keys.removeAll(declared);
        keys.remove("_id");
        if (!keys.isEmpty()) is.warn("extra_fields", spec.isQuery() ? "data.query" : "data.custom.columns", "rows also have " + keys
                + ", which are not declared and will be dropped");

        if (spec.view().primitive().equals("composition") && spec.view().y() != null && !spec.view().y().isEmpty()) {
            String y = spec.view().y().get(0);
            for (JsonNode row : rows) {
                if (row.path(y).isNumber() && row.path(y).asDouble() < 0) {
                    is.error("negative_part", spec.isQuery() ? "data.query" : "data.custom.columns." + y, "composition parts cannot be negative; '" + y
                            + "' is " + row.path(y).asDouble() + " in a row - use bar for values that can go below zero");
                    break;
                }
            }
        }
        boolean series = spec.view().primitive().equals("line") || spec.view().primitive().equals("metric");
        if (series && spec.view().x() != null) {
            String prev = null;
            for (JsonNode row : rows) {
                String x = row.path(spec.view().x()).asText(null);
                if (prev != null && x != null && x.compareTo(prev) < 0) {
                    is.warn("order", spec.isQuery() ? "data.query.sort" : "data.custom.cypher", spec.view().primitive() + " x values are not ascending; "
                            + (spec.isQuery() ? "sort by " + spec.view().x() + " ascending" : "add {\"$sort\": {\"" + spec.view().x() + "\": 1}}"));
                    break;
                }
                if (x != null) prev = x;
            }
        }
    }

    /** Catches 0-100 values formatted as a 0-1 ratio and the reverse - the classic silent percent error. */
    private void checkScale(ResultSet.Column c, List<JsonNode> values, String path, Issues is) {
        double[] abs = values.stream().mapToDouble(v -> Math.abs(v.asDouble())).sorted().toArray();
        double median = abs[abs.length / 2];
        String f = c.formatOrDefault();
        if (f.equals("percent") && median > 2)
            is.error("unit_scale", path, "column '" + c.name() + "' is formatted percent (a 0-1 ratio) but its values look like 0-100 (median "
                    + median + "); use unit percent with format percentPoints, or divide by 100 in the query");
        if (f.equals("percentPoints") && abs[abs.length - 1] <= 1 && median > 0)
            is.warn("unit_scale", path, "column '" + c.name() + "' is formatted percentPoints (0-100) but every value is <= 1; "
                    + "if these are ratios use unit ratio with format percent");
    }

    private static boolean hasType(JsonNode v, String type) {
        return switch (type) {
            case "number" -> v.isNumber();
            case "boolean" -> v.isBoolean();
            case "date" -> v.isTextual() && isInstant(v.asText());
            default -> v.isTextual();
        };
    }

    private static boolean isInstant(String s) {
        try { Instant.parse(s); return true; } catch (DateTimeParseException e) { return false; }
    }

    private static Map<String, ResultSet.Column> byName(List<ResultSet.Column> cols) {
        Map<String, ResultSet.Column> out = new LinkedHashMap<>();
        cols.forEach(c -> out.put(c.name(), c));
        return out;
    }

    private Layout.Page page(String layoutId, String pageId, Issues is) {
        Layout.Page p = layouts.get(layoutId == null ? "default" : layoutId).page(pageId);
        if (p == null) is.error("page", "pageId", "no page '" + pageId + "'");
        return p;
    }

    private static String unitHint(String unit) {
        return switch (unit) {
            case "ratio" -> " (ratio means 0-1, e.g. 0.44)";
            case "percent" -> " (percent means already 0-100, e.g. 44)";
            default -> "";
        };
    }

    private static String pathOf(String schemaMessage) {
        int i = schemaMessage.indexOf(':');
        String p = i > 0 ? schemaMessage.substring(0, i) : "$";
        return p.startsWith("$.") ? p.substring(2) : p;
    }

    /** Collects issues and builds the report. */
    private static final class Issues {
        final List<ValidationReport.Issue> errors = new ArrayList<>();
        final List<ValidationReport.Issue> warnings = new ArrayList<>();

        void error(String code, String path, String msg) { errors.add(new ValidationReport.Issue(code, path, msg)); }
        void warn(String code, String path, String msg) { warnings.add(new ValidationReport.Issue(code, path, msg)); }
        boolean hasErrors() { return !errors.isEmpty(); }

        ValidationReport report(Map<String, String> sample, ValidationReport.Preview preview) {
            return new ValidationReport(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings), sample, preview);
        }
    }
}
