package com.aipb.semantic;

import java.time.LocalDate;
import java.util.List;

/**
 * What a query runs against, supplied by the page rather than the query: the data's today ({@code asOf}), the selected
 * entity (a clientId on Client 360; null on a book-wide page), the page's {@code period} (used by queries whose period
 * is "page") and the page's {@code filters} (a lens such as region = EMEA, applied wherever a query's figures can be
 * filtered that way). Queries never read the wall clock or the URL directly.
 */
public record QueryContext(LocalDate asOf, String entityId, Window period, List<SemanticQuery.Filter> filters) {

    public QueryContext {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public QueryContext(LocalDate asOf, String entityId) { this(asOf, entityId, null, List.of()); }

    public static QueryContext book(LocalDate asOf) { return new QueryContext(asOf, null); }

    public static QueryContext entity(LocalDate asOf, String entityId) { return new QueryContext(asOf, entityId); }
}
