package com.aipb.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Renders the data dictionary (the bank graph's (:_Catalog) entries, one per label) as text for the assistant's
 * prompts. Output is deterministic - same catalog, same bytes - so the system prompts that embed it stay cacheable, and
 * it is generated from the catalog itself, so it can never drift from the data.
 */
public final class CatalogRenderer {

    private CatalogRenderer() {}

    /** The _database entry: what the data is, its conventions, its labels and how they connect. */
    public static String overview(JsonNode catalog) {
        JsonNode db = entry(catalog, "_database");
        StringBuilder sb = new StringBuilder("# The data (the bank graph, Neo4j)\n\n");
        if (db == null) return sb.append("(no overview available)\n").toString();
        sb.append(db.path("description").asText()).append("\n\n## Conventions\n");
        db.path("conventions").forEach(c -> sb.append("- ").append(c.asText()).append('\n'));
        sb.append("\n## Labels\n");
        db.path("labels").fields().forEachRemaining(e -> sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n'));
        sb.append("\n## Relationships\nPath patterns, read in the direction of the arrow. Many-to-one: following it never multiplies rows.\n");
        db.path("patterns").forEach(p -> sb.append("- ").append(p.asText()).append('\n'));
        return sb.toString();
    }

    /** A compact index for deciding feasibility: per label, its purpose, properties (type, unit, values) and derived metrics. */
    public static String index(JsonNode catalog) {
        StringBuilder sb = new StringBuilder("# Data index\n\nEvery label, its properties and the metrics derived from it.\n");
        for (JsonNode e : labels(catalog)) {
            sb.append("\n## ").append(e.path("_id").asText()).append('\n').append(firstSentence(e.path("description").asText())).append('\n');
            List<String> props = new ArrayList<>();
            for (JsonNode f : e.path("properties")) {
                String name = f.path("name").asText();
                if (name.equals("_id")) continue;
                StringBuilder fs = new StringBuilder(name).append(" (").append(shortType(f));
                if (f.has("allowedValues") && f.get("allowedValues").size() <= 8)
                    fs.append(": ").append(String.join("|", strings(f.get("allowedValues"))));
                props.add(fs.append(')').toString());
            }
            sb.append("Properties: ").append(String.join(", ", props)).append('\n');
            List<String> rels = new ArrayList<>();
            e.path("relationships").forEach(r -> rels.add(r.path("pattern").asText()));
            if (!rels.isEmpty()) sb.append("Relationships: ").append(String.join(", ", rels)).append('\n');
            List<String> derived = new ArrayList<>();
            e.path("derivedMetrics").forEach(d -> derived.add(d.path("name").asText()));
            if (!derived.isEmpty()) sb.append("Derived: ").append(String.join(", ", derived)).append('\n');
        }
        return sb.toString();
    }

    /** The full dictionary: every property with meaning, unit and allowed values, every relationship, and every derived metric's definition. */
    public static String dictionary(JsonNode catalog) {
        StringBuilder sb = new StringBuilder("# Data dictionary\n\nThe authoritative meaning of every label, property and relationship. "
                + "Follow derived-metric definitions exactly.\n");
        for (JsonNode e : labels(catalog)) {
            sb.append("\n## ").append(e.path("_id").asText()).append('\n').append(e.path("description").asText()).append('\n');
            sb.append("Grain: ").append(e.path("grain").asText()).append(". Key: ").append(e.path("key").asText())
                    .append(e.has("keyDescription") ? " (" + e.path("keyDescription").asText() + ")" : "").append('.');
            List<String> rel = new ArrayList<>();
            e.path("relationships").forEach(r -> rel.add(r.path("pattern").asText() + " on " + r.path("via").asText()
                    + ("many-to-one".equals(r.path("cardinality").asText()) ? "" : ", " + r.path("cardinality").asText())));
            if (!rel.isEmpty()) sb.append(" Relationships: ").append(String.join("; ", rel)).append('.');
            sb.append("\nProperties:\n");
            for (JsonNode f : e.path("properties")) {
                if (f.path("name").asText().equals("_id")) continue;
                field(sb, f, "- ");
                f.path("itemFields").forEach(i -> field(sb, i, "  - ." ));
            }
            if (e.path("derivedMetrics").size() > 0) {
                sb.append("Derived metrics (not stored - compute them):\n");
                e.path("derivedMetrics").forEach(d -> sb.append("- ").append(d.path("name").asText()).append(": ")
                        .append(d.path("definition").asText()).append('\n'));
            }
        }
        return sb.toString();
    }

    /** Property and derived-metric names per label, for checking catalogReference values. */
    public static Map<String, Set<String>> referenceNames(JsonNode catalog) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (JsonNode e : labels(catalog)) {
            Set<String> names = new TreeSet<>();
            e.path("properties").forEach(f -> {
                names.add(f.path("name").asText());
                f.path("itemFields").forEach(i -> names.add(f.path("name").asText() + "." + i.path("name").asText()));
            });
            // a derived metric may name several figures at once, e.g. "niiYtdUsd / feeYtdUsd"
            e.path("derivedMetrics").forEach(d -> Arrays.stream(d.path("name").asText().split("/"))
                    .map(String::trim).filter(s -> !s.isEmpty()).forEach(names::add));
            out.put(e.path("_id").asText(), names);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private static void field(StringBuilder sb, JsonNode f, String prefix) {
        sb.append(prefix).append(f.path("name").asText()).append(" (").append(shortType(f)).append(')');
        if (f.hasNonNull("description")) sb.append(": ").append(f.get("description").asText());
        if ("json".equals(f.path("storage").asText())) sb.append(" Stored as JSON text: read it whole; it cannot be filtered on.");
        if (f.has("allowedValues")) sb.append(" Values: ").append(String.join(", ", strings(f.get("allowedValues")))).append('.');
        if (f.has("example")) sb.append(" e.g. ").append(f.get("example").asText());
        sb.append('\n');
    }

    private static String shortType(JsonNode f) {
        String t = f.path("type").asText();
        String unit = f.path("unit").asText("");
        return unit.isEmpty() ? t : t + ", " + unit;
    }

    private static List<JsonNode> labels(JsonNode catalog) {
        List<JsonNode> out = new ArrayList<>();
        catalog.forEach(e -> { if (!e.path("_id").asText().startsWith("_")) out.add(e); });
        out.sort(Comparator.comparing(e -> e.path("_id").asText()));
        return out;
    }

    private static JsonNode entry(JsonNode catalog, String id) {
        for (JsonNode e : catalog) if (id.equals(e.path("_id").asText())) return e;
        return null;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(v -> out.add(v.asText()));
        return out;
    }

    private static String firstSentence(String s) {
        int i = s.indexOf(". ");
        return i > 0 ? s.substring(0, i + 1) : s;
    }
}
