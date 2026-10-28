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

    public record Saved(WidgetDefinition definition, ValidationReport report) {}

    /** The global library: approved and stock widgets, live for everyone. */
    public List<WidgetDefinition> list() {
        return repo.findAll().stream().filter(WidgetDefinition::global).sorted(Comparator.comparing(WidgetDefinition::getTitle)).toList();
    }

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

    /**
     * Saves a new definition owned by {@code owner}, in its personal library or the global one ({@code scope}). A
     * personal widget is a draft: no version history until a Reviewer approves it (see {@link WidgetWorkflow}).
     */
    public Saved create(JsonNode spec, String author, String owner, String scope, String layoutId, String pageId, Map<String, String> params) {
        return create(spec, author, owner, scope, layoutId, pageId, params, null);
    }

    /** As {@link #create(JsonNode, String, String, String, String, String, Map)}, recording the skill's kind and template. */
    public Saved create(JsonNode spec, String author, String owner, String scope, String layoutId, String pageId, Map<String, String> params,
                        WidgetDefinition.SkillOrigin origin) {
        String id = spec.path("id").asText();
        if (repo.existsById(id)) throw new DuplicateException("a widget '" + id + "' already exists; update it, or pick another id");
        Checked c = check(spec, 1, author, layoutId, pageId, params);
        if (!WidgetDefinition.PERSONAL.equals(scope))
            return new Saved(publish(c, 1, WidgetDefinition.BUILT, WidgetVersion.APPROVED, null, null, null, owner, origin), c.report());
        return new Saved(repo.save(draft(c, owner, null, null, origin)), c.report());
    }

    /** A revised draft of the actor's own personal widget; ownership and review carry over. */
    public Saved update(String id, JsonNode spec, String author, String actorId, String layoutId, String pageId, Map<String, String> params) {
        WidgetDefinition current = get(id, actorId);
        if (!id.equals(spec.path("id").asText()))
            throw new IllegalArgumentException("spec id '" + spec.path("id").asText() + "' does not match '" + id + "'");
        if (current.global()) throw new IllegalArgumentException("'" + current.getTitle() + "' is in the global library; changes to it go through review");
        if ("rejected".equals(current.reviewStatus())) throw new IllegalArgumentException("'" + current.getTitle() + "' was rejected; build a new widget instead");
        Checked c = check(spec, 1, author, layoutId, pageId, params);
        return new Saved(repo.save(draft(c, current.getOwner(), current.getReview(), current.getCreatedAt(), current.skillOrigin())), c.report());
    }

    /**
     * Asks for a personal widget to be published to the global library. It stays personal until a Reviewer approves
     * it. After feedback & revise it can be submitted again.
     */
    public WidgetDefinition submitForReview(String id, String actorId, String note) {
        WidgetDefinition d = get(id, actorId);
        if (d.global()) throw new IllegalArgumentException("'" + d.getTitle() + "' is already in the global library");
        if (!actorId.equals(d.getOwner())) throw new IllegalArgumentException("only the owner can submit '" + d.getTitle() + "' for review");
        if ("pending".equals(d.reviewStatus())) throw new IllegalArgumentException("'" + d.getTitle() + "' is already waiting for review");
        if ("rejected".equals(d.reviewStatus())) throw new IllegalArgumentException("'" + d.getTitle() + "' was rejected and cannot be submitted again");
        WidgetDefinition.Review r = d.getReview() == null ? new WidgetDefinition.Review() : d.getReview();
        r.setStatus("pending");
        r.setSubmittedBy(actorId);
        r.setSubmittedAt(Instant.now());
        r.setNote(note == null || note.isBlank() ? null : note.trim());
        d.setReview(r);
        d.setUpdatedAt(Instant.now());
        return repo.save(d);
    }

    /** Records the review outcome on a personal widget (feedback & revise, rejected) and the review it belongs to. */
    public void markReview(String id, String status, String submissionId) {
        repo.findById(id).filter(d -> !d.global()).ifPresent(d -> {
            WidgetDefinition.Review r = d.getReview() == null ? new WidgetDefinition.Review() : d.getReview();
            r.setStatus(status);
            if (submissionId != null) r.setSubmissionId(submissionId);
            d.setReview(r);
            d.setUpdatedAt(Instant.now());
            repo.save(d);
        });
    }

    private WidgetDefinition draft(Checked c, String owner, WidgetDefinition.Review review, Instant createdAt,
                                   WidgetDefinition.SkillOrigin skill) {
        WidgetSpec s = c.parsed();
        WidgetDefinition d = new WidgetDefinition();
        d.setId(s.id());
        d.setVersion(1);
        d.setOrigin(WidgetDefinition.BUILT);
        d.setScope(WidgetDefinition.PERSONAL);
        d.setTitle(s.title());
        d.setDescription(s.description());
        d.setPrimitive(s.view().primitive());
        d.setCollection(data.sourceLabel(s));
        d.setParams(data.requiredParams(s));
        d.setAuthor(c.spec().path("provenance").path("author").asText(null));
        d.setOwner(owner);
        d.setReview(review);
        d.setSkillOrigin(skill);
        d.setSpec(c.spec().toString());
        d.setCreatedAt(createdAt == null ? Instant.now() : createdAt);
        d.setUpdatedAt(Instant.now());
        return d;
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
     * Publishes a checked spec as version {@code version} of its widget: written to the history and made the live,
     * global copy (a personal widget being approved becomes global here). {@code source} says where the version came
     * from (see {@link WidgetVersion}).
     */
    public WidgetDefinition publish(Checked c, int version, String origin, String source, String submissionId,
                                    Integer restoredFrom, String role, String owner) {
        return publish(c, version, origin, source, submissionId, restoredFrom, role, owner, null);
    }

    /** As above; {@code skill} records the kind and template of a widget published for the first time (a later version keeps its own). */
    public WidgetDefinition publish(Checked c, int version, String origin, String source, String submissionId,
                                    Integer restoredFrom, String role, String owner, WidgetDefinition.SkillOrigin skill) {
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
        d.setScope(WidgetDefinition.GLOBAL);
        if (current != null && current.getReview() != null) {
            current.getReview().setStatus("approved");
            d.setReview(current.getReview());
        }
        d.setSkillOrigin(current != null ? current.skillOrigin() : skill);
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
