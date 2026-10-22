package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * The widget contract: the spec JSON Schema (resources/widgets/widget-spec.schema.json) and the design-system
 * primitives catalog (resources/widgets/primitives.json). Both are served by the widget manifest, so the workbench,
 * the validator and - later - the assistant all read the same rules.
 */
@Component
public class WidgetPrimitives {

    private final JsonNode schemaJson;
    private final JsonNode catalog;
    private final JsonSchema schema;

    public WidgetPrimitives(ObjectMapper mapper) {
        this.schemaJson = read(mapper, "widgets/widget-spec.schema.json");
        this.catalog = read(mapper, "widgets/primitives.json");
        this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson);
    }

    public JsonNode schemaJson() { return schemaJson; }

    public JsonNode catalog() { return catalog; }

    /** Schema violations of a spec, each as "path: message". Empty when the spec is structurally valid. */
    public List<String> schemaErrors(JsonNode spec) {
        Set<ValidationMessage> msgs = schema.validate(spec);
        return msgs.stream().map(ValidationMessage::getMessage).sorted().toList();
    }

    public JsonNode primitive(String name) { return catalog.path("primitives").path(name); }

    /** Formats allowed for a unit (e.g. USD -> currency). */
    public List<String> formatsForUnit(String unit) { return strings(catalog.path("units").path(unit).path("formats")); }

    /** Column types a format can display (e.g. currency -> number). */
    public List<String> typesForFormat(String format) { return strings(catalog.path("formats").path(format).path("types")); }

    public Set<String> builtinParams() {
        Set<String> out = new LinkedHashSet<>();
        catalog.path("builtinParams").fieldNames().forEachRemaining(out::add);
        return out;
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static JsonNode read(ObjectMapper mapper, String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
