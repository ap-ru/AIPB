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

    /** The definitions an actor can see: the global library plus their own personal widgets. */
    public List<WidgetDefinition> list(String actorId) {
        return repo.findAll().stream().filter(d -> d.visibleTo(actorId)).sorted(Comparator.comparing(WidgetDefinition::getTitle)).toList();
    }

    /** A definition the actor can see; someone else's personal widget is reported as not found. */
    public WidgetDefinition get(String id, String actorId) {
        WidgetDefinition d = get(id);
        if (!d.visibleTo(actorId)) throw new NoSuchElementException("widget definition " + id);
        return d;
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

    /** Saves a new definition owned by {@code owner}, in its personal library or the global one ({@code scope}). */
    public Saved create(JsonNode spec, String author, String owner, String scope, String layoutId, String pageId, Map<String, String> params) {
        return create(spec, author, owner, scope, layoutId, pageId, params, null);
    }

    /** As {@link #create(JsonNode, String, String, String, String, String, Map)}, recording the skill's kind and template. */
    public Saved create(JsonNode spec, String author, String owner, String scope, String layoutId, String pageId, Map<String, String> params,
                        WidgetDefinition.Origin origin) {
        String id = spec.path("id").asText();
        if (repo.existsById(id)) throw new DuplicateException("a widget '" + id + "' already exists; update it, or pick another id");
        return save(spec, 1, author, owner, scope, null, layoutId, pageId, params, null, origin);
    }

    /** A new version of a definition; a personal one can only be changed by its owner. Ownership and review carry over. */
    public Saved update(String id, JsonNode spec, String author, String actorId, String layoutId, String pageId, Map<String, String> params) {
        WidgetDefinition current = get(id, actorId);
        if (!id.equals(spec.path("id").asText()))
            throw new IllegalArgumentException("spec id '" + spec.path("id").asText() + "' does not match '" + id + "'");
        return save(spec, current.getVersion() + 1, author, current.getOwner(), current.getScope(), current.getReview(),
                layoutId, pageId, params, current.getCreatedAt(),
                new WidgetDefinition.Origin(current.getKind(), current.getTemplateId(), current.getTemplateVersion()));
    }

    /**
     * Asks for a personal widget to be published to the global library. It stays personal until a Reviewer approves it.
     */
    public WidgetDefinition submitForReview(String id, String actorId, String note) {
        WidgetDefinition d = get(id, actorId);
        if (d.global()) throw new IllegalArgumentException("'" + d.getTitle() + "' is already in the global library");
        if (!actorId.equals(d.getOwner())) throw new IllegalArgumentException("only the owner can submit '" + d.getTitle() + "' for review");
        if ("pending".equals(d.reviewStatus())) throw new IllegalArgumentException("'" + d.getTitle() + "' is already waiting for review");
        WidgetDefinition.Review r = new WidgetDefinition.Review();
        r.setStatus("pending");
        r.setSubmittedBy(actorId);
        r.setSubmittedAt(Instant.now());
        r.setNote(note == null || note.isBlank() ? null : note.trim());
        d.setReview(r);
        d.setUpdatedAt(Instant.now());
        return repo.save(d);
    }

    private Saved save(JsonNode specIn, int version, String author, String owner, String scope, WidgetDefinition.Review review,
                       String layoutId, String pageId, Map<String, String> params, Instant createdAt, WidgetDefinition.Origin origin) {
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
        d.setOwner(owner);
        d.setScope(scope);
        d.setReview(review);
        if (origin != null) {
            d.setKind(origin.kind());
            d.setTemplateId(origin.templateId());
            d.setTemplateVersion(origin.templateVersion());
        }
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
                save(spec, fileVersion, "starter", "system", WidgetDefinition.GLOBAL, null, "default", null, sample, stored == null ? null : stored.getCreatedAt(), null);
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
