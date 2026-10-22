package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;

/**
 * The widget definition library (aipb.widgets). Nothing is saved without passing {@link WidgetSpecValidator}: a spec
 * with errors is refused with its report. Saving bumps the version and stamps provenance. At startup the starter
 * specs (resources/widgets/starter/*.json) are loaded the same way - they rebuild Client 360 from the source data and
 * serve as worked examples.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetLibrary implements ApplicationRunner {

    private final WidgetDefinitionRepository repo;
    private final WidgetSpecValidator validator;
    private final WidgetDataService data;
    private final ObjectMapper mapper;

    /** A refused save: the spec did not pass validation. */
    public static class SpecInvalidException extends RuntimeException {
        private final transient ValidationReport report;
        public SpecInvalidException(ValidationReport report) {
            super("the widget spec has " + report.errors().size() + " error(s)");
            this.report = report;
        }
        public ValidationReport report() { return report; }
    }

    /** A create for an id that already exists. */
    public static class DuplicateException extends RuntimeException {
        public DuplicateException(String msg) { super(msg); }
    }

    public record Saved(WidgetDefinition definition, ValidationReport report) {}

    public List<WidgetDefinition> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(WidgetDefinition::getTitle)).toList();
    }

    public WidgetDefinition get(String id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("widget definition " + id));
    }

    /** A definition's spec, in the current format. */
    public JsonNode spec(WidgetDefinition d) {
        try {
            return SpecMigrator.migrate(mapper.readTree(d.getSpec()));
        } catch (IOException e) {
            throw new IllegalStateException("stored spec of " + d.getId() + " is not valid JSON", e);
        }
    }

    public Saved create(JsonNode spec, String author, String layoutId, String pageId, Map<String, String> params) {
        String id = spec.path("id").asText();
        if (repo.existsById(id)) throw new DuplicateException("a widget '" + id + "' already exists; update it, or pick another id");
        return save(spec, 1, author, layoutId, pageId, params, null);
    }

    public Saved update(String id, JsonNode spec, String author, String layoutId, String pageId, Map<String, String> params) {
        WidgetDefinition current = get(id);
        if (!id.equals(spec.path("id").asText()))
            throw new IllegalArgumentException("spec id '" + spec.path("id").asText() + "' does not match '" + id + "'");
        return save(spec, current.getVersion() + 1, author, layoutId, pageId, params, current.getCreatedAt());
    }

    private Saved save(JsonNode specIn, int version, String author, String layoutId, String pageId,
                       Map<String, String> params, Instant createdAt) {
        ObjectNode spec = (ObjectNode) SpecMigrator.migrate(specIn);
        spec.put("version", version);
        ValidationReport report = validator.validate(spec, layoutId, pageId, params);
        if (!report.ok()) throw new SpecInvalidException(report);

        ObjectNode prov = spec.has("provenance") && spec.get("provenance").isObject()
                ? (ObjectNode) spec.get("provenance") : spec.putObject("provenance");
        prov.put("author", author);
        prov.put("savedAt", Instant.now().toString());
        if (report.preview() != null && report.preview().asOfDate() != null) prov.put("validatedAsOf", report.preview().asOfDate());

        WidgetSpec s = SpecMigrator.read(mapper, spec);
        WidgetDefinition d = new WidgetDefinition();
        d.setId(s.id());
        d.setVersion(version);
        d.setTitle(s.title());
        d.setDescription(s.description());
        d.setPrimitive(s.view().primitive());
        d.setCollection(data.sourceLabel(s));
        d.setParams(data.requiredParams(s));
        d.setAuthor(author);
        d.setSpec(spec.toString());
        d.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        d.setUpdatedAt(Instant.now());
        return new Saved(repo.save(d), report);
    }

    // ------------------------------------------------------------------ starter specs
    /** Loads each starter spec that is new, or newer than the stored one. Sample values come from provenance.sampleParams. */
    @Override
    public void run(ApplicationArguments args) {
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources("classpath:widgets/starter/*.json");
        } catch (IOException e) {
            log.warn("cannot list starter widget specs: {}", e.getMessage());
            return;
        }
        int loaded = 0;
        int skipped = 0;
        for (Resource f : files) {
            try (InputStream in = f.getInputStream()) {
                JsonNode spec = mapper.readTree(in);
                String id = spec.path("id").asText();
                int fileVersion = spec.path("version").asInt(1);
                WidgetDefinition stored = repo.findById(id).orElse(null);
                if (stored != null && stored.getVersion() >= fileVersion) continue;
                Map<String, String> sample = new LinkedHashMap<>();
                spec.path("provenance").path("sampleParams").fields().forEachRemaining(e -> sample.put(e.getKey(), e.getValue().asText()));
                save(spec, fileVersion, "starter", "default", null, sample, stored == null ? null : stored.getCreatedAt());
                loaded++;
            } catch (SpecInvalidException e) {
                skipped++;
                log.error("starter widget {} is invalid and was not loaded: {}", f.getFilename(),
                        e.report().errors().stream().map(i -> i.code() + " " + i.path() + ": " + i.message()).toList());
            } catch (Exception e) {
                skipped++;
                log.error("starter widget {} could not be loaded: {}", f.getFilename(), e.getMessage());
            }
        }
        log.info("Starter widgets: {} loaded, {} skipped, {} files", loaded, skipped, files.length);
    }
}
