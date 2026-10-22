package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads every spec format as the current one. Format 1 (no specVersion; data = collection + pipeline + columns) is
 * exactly format 2's custom data, so the move is lossless: data becomes {custom: {collection, pipeline, columns}}.
 * Everything that accepts a spec - the validator, the library, the assistant's gates - reads through here, so stored
 * definitions, the workbench and the assistant keep working while the format moves on.
 */
public final class SpecMigrator {

    public static final int CURRENT = 2;

    private SpecMigrator() {}

    /** A copy of {@code spec} in the current format (the input is never modified). Non-objects are returned as they are. */
    public static JsonNode migrate(JsonNode spec) {
        if (spec == null || !spec.isObject()) return spec;
        ObjectNode out = spec.deepCopy();
        if (out.path("specVersion").asInt(1) >= CURRENT) return out;
        JsonNode data = out.path("data");
        if (data.isObject() && (data.has("collection") || data.has("pipeline")) && !data.has("custom") && !data.has("query")) {
            ObjectNode custom = ((ObjectNode) data).objectNode();
            for (String k : new String[]{"collection", "pipeline", "columns"}) if (data.has(k)) custom.set(k, data.get(k));
            ObjectNode d = out.putObject("data");
            d.set("custom", custom);
        }
        ObjectNode ordered = out.objectNode();
        if (out.has("id")) ordered.set("id", out.get("id"));
        ordered.put("specVersion", CURRENT);
        out.fields().forEachRemaining(e -> { if (!e.getKey().equals("id") && !e.getKey().equals("specVersion")) ordered.set(e.getKey(), e.getValue()); });
        return ordered;
    }

    /** Parses a spec of any format; call only after the migrated JSON passed schema validation (or for stored specs). */
    public static WidgetSpec read(ObjectMapper mapper, JsonNode spec) {
        return mapper.convertValue(migrate(spec), WidgetSpec.class);
    }
}
