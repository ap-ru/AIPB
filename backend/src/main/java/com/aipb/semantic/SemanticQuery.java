package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * What a widget asks for, in business terms - the IR between a request and the database. Validated and compiled by
 * {@link QueryPlanner}; never contains a pipeline.
 * <ul>
 *   <li>{@code aggregate}: {@code metrics} (measure or derived metric ids) broken down by {@code dimensions} (conformed
 *       dimension ids, including the time grains month / quarter / year), over a {@code window}, optionally with each
 *       metric's value over the {@code compare} window (priorYear | priorPeriod) as {@code <metric>Prior}.</li>
 *   <li>{@code records}: rows of one {@code model}, its record {@code fields}, filtered, sorted and limited.</li>
 * </ul>
 * {@code filters} test a dimension (or, for records, a record field); {@code segments} name a model's predefined
 * subsets (open deals, active signals). {@code scope}: {@code inherit} (default) applies the page's entity (the
 * selected client) when it has one; {@code book} never does; {@code entity} requires one.
 * <p>Page context: {@code period} {@code page} uses the page's period when it has one ({@code window} is then the
 * default), {@code fixed} (default) always uses {@code window}. The page's filters apply unless {@code pageFilters} is
 * false; a page filter a query's figures cannot take is reported as a note, never silently dropped.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SemanticQuery(String kind, List<String> metrics, List<String> dimensions, String model, List<String> fields,
                            List<Filter> filters, List<String> segments, Window window, String compare, List<Sort> sort,
                            Integer limit, String scope, String period, Boolean pageFilters) {

    public static final int DEFAULT_RECORD_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Filter(String on, String op, JsonNode value) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sort(String by, String dir) {
        public boolean descending() { return "desc".equals(dir); }
    }

    public boolean records() { return "records".equals(kind); }
    public List<String> metricsOrEmpty() { return metrics == null ? List.of() : metrics; }
    public List<String> dimensionsOrEmpty() { return dimensions == null ? List.of() : dimensions; }
    public List<String> fieldsOrEmpty() { return fields == null ? List.of() : fields; }
    public List<Filter> filtersOrEmpty() { return filters == null ? List.of() : filters; }
    public List<String> segmentsOrEmpty() { return segments == null ? List.of() : segments; }
    public List<Sort> sortOrEmpty() { return sort == null ? List.of() : sort; }
    public String scopeOrDefault() { return scope == null ? "inherit" : scope; }
    public boolean followsPagePeriod() { return "page".equals(period); }
    public boolean takesPageFilters() { return pageFilters == null || pageFilters; }

    /** This query with another window (e.g. the page's period). */
    public SemanticQuery withWindow(Window w) {
        return new SemanticQuery(kind, metrics, dimensions, model, fields, filters, segments, w, compare, sort, limit, scope, period, pageFilters);
    }
}
