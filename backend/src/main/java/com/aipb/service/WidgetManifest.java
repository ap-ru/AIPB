package com.aipb.service;

import java.util.*;

/**
 * What the dynamic UI can render: every widget type with its config schema. Served to the frontend configure panel.
 */
public final class WidgetManifest {

    private WidgetManifest() {}

    public static final List<String> LOBS = List.of("Corporate Banking", "Commercial Lending", "Capital Markets", "Global Markets");
    public static final List<String> REGIONS = List.of("AMER", "EMEA", "APAC", "LATAM");
    static final List<String> CLIENT_SORTS = List.of("revenue", "exposure", "health", "walletGap", "pipeline");

    /** A widget type. {@code config} maps each config key to its allowed values (null = free value). */
    public record WidgetType(String type, String label, String description, Map<String, List<String>> config, int defaultSpan) {}

    public static final Map<String, WidgetType> TYPES = new LinkedHashMap<>();

    static {
        type("kpi-strip", "KPI strip", "Row of book-level KPI tiles.", 12,
                keys("set", List.of("book"), "lob", LOBS, "region", REGIONS));
        type("client-table", "Client book", "Client book table (links to Client 360).", 12,
                keys("lob", LOBS, "region", REGIONS, "marketId", null, "sort", CLIENT_SORTS, "limit", null));
        type("client-360", "Client 360 relationship view",
                "Full relationship view for one client with a client picker: health, ratings, KPIs, revenue trend, LOB mix, holdings, covenants, deals, signals, contacts.", 12,
                keys("clientId", null, "lob", LOBS, "region", REGIONS, "marketId", null, "sort", CLIENT_SORTS));
    }

    /** The manifest as JSON-ready maps, served to the frontend. */
    public static Map<String, Object> describe() {
        Map<String, Object> types = new LinkedHashMap<>();
        TYPES.values().forEach(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", t.label());
            m.put("description", t.description());
            m.put("defaultSpan", t.defaultSpan());
            Map<String, Object> cfg = new LinkedHashMap<>();
            t.config().forEach((k, v) -> cfg.put(k, v == null ? "free" : v));
            m.put("config", cfg);
            types.put(t.type(), m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("lobs", LOBS);
        out.put("regions", REGIONS);
        return out;
    }

    private static void type(String type, String label, String description, int span, Map<String, List<String>> config) {
        TYPES.put(type, new WidgetType(type, label, description, config, span));
    }

    static Map<String, List<String>> keys(Object... kv) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            @SuppressWarnings("unchecked") List<String> v = (List<String>) kv[i + 1];
            m.put((String) kv[i], v);
        }
        return m;
    }
}
