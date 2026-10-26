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
 * The widget definition library (aipb.widgets) and its version history (aipb.widget_versions). Nothing is published
 * without passing {@link WidgetSpecValidator}: a spec with errors is refused with its report. Publishing writes the
 * version to the history and makes it the widget's live copy, which every page placing the widget then shows. Only
 * {@link WidgetWorkflow} publishes changes made in the app (once approved); at startup the starter specs
 * (resources/widgets/starter/*.json) are loaded as stock widgets - they rebuild Client 360 from the source data and
 * serve as worked examples.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WidgetLibrary implements ApplicationRunner {

    private final WidgetDefinitionRepository repo;
    private final WidgetVersionRepository versions;
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

    /** A spec that passed validation, stamped with its version and provenance, ready to publish or submit. */
    public record Checked(ObjectNode spec, WidgetSpec parsed, ValidationReport report) {}

    public List<WidgetDefinition> list() {
        return repo.findAll().stream().sorted(Comparator.comparing(WidgetDefinition::getTitle)).toList();
    }

    public WidgetDefinition get(String id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("widget definition " + id));
    }

    public Optional<WidgetDefinition> find(String id) { return repo.findById(id); }

    public boolean exists(String id) { return repo.existsById(id); }

    /** A definition's spec, in the current format. */
    public JsonNode spec(WidgetDefinition d) { return parse(d.getSpec(), d.getId()); }

    /** Every version of a widget, newest first. */
    public List<WidgetVersion> history(String id) {
        get(id);
        return versions.findByWidgetIdOrderByVersionDesc(id);
    }

    public WidgetVersion version(String id, int version) {
        return versions.findById(WidgetVersion.key(id, version))
                .orElseThrow(() -> new NoSuchElementException("version " + version + " of widget " + id));
    }

    public JsonNode spec(WidgetVersion v) { return parse(v.getSpec(), v.getWidgetId()); }

    /** Validates a spec and stamps it with its version and provenance; refuses it with its report if it has errors. */
    public Checked check(JsonNode specIn, int version, String author, String layoutId, String pageId, Map<String, String> params) {
        ObjectNode spec = (ObjectNode) SpecMigrator.migrate(specIn);
        spec.put("version", version);
        ValidationReport report = validator.validate(spec, layoutId, pageId, params);
        if (!report.ok()) throw new SpecInvalidException(report);

        ObjectNode prov = spec.has("provenance") && spec.get("provenance").isObject()
                ? (ObjectNode) spec.get("provenance") : spec.putObject("provenance");
        prov.put("author", author);
        prov.put("savedAt", Instant.now().toString());
        if (report.preview() != null && report.preview().asOfDate() != null) prov.put("validatedAsOf", report.preview().asOfDate());
        if (params != null && !params.isEmpty() && !prov.has("sampleParams")) prov.set("sampleParams", mapper.valueToTree(params));
        return new Checked(spec, SpecMigrator.read(mapper, spec), report);
    }

    /**
     * Publishes a checked spec as version {@code version} of its widget: written to the history and made the live copy.
     * {@code source} says where the version came from (see {@link WidgetVersion}).
     */
    public WidgetDefinition publish(Checked c, int version, String origin, String source, String submissionId,
                                    Integer restoredFrom, String role, String owner) {
        c.spec().put("version", version);
        WidgetSpec s = c.parsed();
        WidgetDefinition current = repo.findById(s.id()).orElse(null);

        WidgetVersion v = new WidgetVersion();
        v.setId(WidgetVersion.key(s.id(), version));
        v.setWidgetId(s.id());
        v.setVersion(version);
        v.setTitle(s.title());
        v.setDescription(s.description());
        v.setPrimitive(s.view().primitive());
        v.setSpec(c.spec().toString());
        v.setSource(source);
        v.setSubmissionId(submissionId);
        v.setRestoredFrom(restoredFrom);
        v.setApprovedByRole(role);
        v.setCreatedAt(Instant.now());
        versions.save(v);

        WidgetDefinition d = new WidgetDefinition();
        d.setId(s.id());
        d.setVersion(version);
        d.setOrigin(current != null && current.getOrigin() != null ? current.getOrigin() : origin);
        d.setTitle(s.title());
        d.setDescription(s.description());
        d.setPrimitive(s.view().primitive());
        d.setCollection(data.sourceLabel(s));
        d.setParams(data.requiredParams(s));
        d.setAuthor(c.spec().path("provenance").path("author").asText(null));
        d.setOwner(owner != null ? owner : current != null ? current.getOwner() : null);
        d.setSpec(c.spec().toString());
        d.setCreatedAt(current == null ? Instant.now() : current.getCreatedAt());
        d.setUpdatedAt(Instant.now());
        return repo.save(d);
    }

    /** Sample parameter values a widget was saved with, for previews outside a page. */
    public Map<String, String> sampleParams(JsonNode spec) {
        Map<String, String> sample = new LinkedHashMap<>();
        spec.path("provenance").path("sampleParams").fields().forEachRemaining(e -> sample.put(e.getKey(), e.getValue().asText()));
        return sample;
    }

    private JsonNode parse(String json, String id) {
        try {
            return SpecMigrator.migrate(mapper.readTree(json));
        } catch (IOException e) {
            throw new IllegalStateException("stored spec of " + id + " is not valid JSON", e);
        }
    }

    // ------------------------------------------------------------------ startup
    @Override
    public void run(ApplicationArguments args) {
        migrate();
        loadStarters();
    }

    /** Widgets saved before versions were kept get their current spec recorded as their first known version. */
    void migrate() {
        int n = 0;
        for (WidgetDefinition d : repo.findAll()) {
            boolean dirty = false;
            if (d.getOrigin() == null) {
                d.setOrigin("starter".equals(d.getAuthor()) ? WidgetDefinition.STOCK : WidgetDefinition.BUILT);
                dirty = true;
            }
            if (!versions.existsById(WidgetVersion.key(d.getId(), d.getVersion()))) {
                WidgetVersion v = new WidgetVersion();
                v.setId(WidgetVersion.key(d.getId(), d.getVersion()));
                v.setWidgetId(d.getId());
                v.setVersion(d.getVersion());
                v.setTitle(d.getTitle());
                v.setDescription(d.getDescription());
                v.setPrimitive(d.getPrimitive());
                v.setSpec(d.getSpec());
                v.setSource(WidgetDefinition.STOCK.equals(d.getOrigin()) ? WidgetVersion.STOCK : WidgetVersion.MIGRATED);
                v.setCreatedAt(d.getUpdatedAt() == null ? Instant.now() : d.getUpdatedAt());
                versions.save(v);
                n++;
            }
            if (dirty) repo.save(d);
        }
        if (n > 0) log.info("Widget versions: recorded {} existing widget(s) as their first version", n);
    }

    /**
     * Loads each starter spec that is new, or newer than the stored one, as a stock version. A stock widget changed in
     * the app keeps its approved versions: a newer file for it is skipped (and logged). Sample values come from
     * provenance.sampleParams.
     */
    void loadStarters() {
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
                if (stored != null && changedInApp(id)) {
                    skipped++;
                    log.warn("starter widget {} v{} not loaded: the widget has versions approved in the app (now v{})", id, fileVersion, stored.getVersion());
                    continue;
                }
                Checked c = check(spec, fileVersion, "starter", "default", null, sampleParams(spec));
                publish(c, fileVersion, WidgetDefinition.STOCK, WidgetVersion.STOCK, null, null, null, "system");
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

    private boolean changedInApp(String id) {
        return versions.findByWidgetIdOrderByVersionDesc(id).stream().anyMatch(v -> !WidgetVersion.STOCK.equals(v.getSource()));
    }
}
