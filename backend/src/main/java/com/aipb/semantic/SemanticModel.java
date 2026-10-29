package com.aipb.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The semantic layer: the one place business meaning lives. Loaded from resources/semantic - conformed dimensions
 * (dimensions.json), one model per node label of the bank graph (models/*.json) and derived metrics (metrics.json). Measures and
 * derived metrics share one namespace of metric ids, so a query just names {@code revenueUsd} or {@code utilization}.
 *
 * <p>Models of client-owned rows inherit the entity model's dimensions (region, industry, tier, ...) through an
 * implicit many-to-one join named after the entity dimension ({@code client}), so they are declared once.
 *
 * <p>Immutable once loaded. Structural problems fail the load (the files are developer-authored); checks against the
 * live data dictionary are separate ({@link SemanticModelValidator#againstCatalog}), since the source may be down.
 */
public final class SemanticModel {

    public static final String RESOURCE_ROOT = "semantic/";

    /** A measure and the model it belongs to. */
    public record MeasureRef(Model model, Model.Measure measure) {}

    private final Map<String, Dimension> dimensions;
    private final Map<String, Model> models;
    private final Map<String, DerivedMetric> derived;
    private final Map<String, MeasureRef> measures;
    private final String fingerprint;

    private SemanticModel(Map<String, Dimension> dimensions, Map<String, Model> models, Map<String, DerivedMetric> derived,
                          String fingerprint) {
        this.dimensions = Collections.unmodifiableMap(dimensions);
        this.models = Collections.unmodifiableMap(models);
        this.derived = Collections.unmodifiableMap(derived);
        Map<String, MeasureRef> m = new LinkedHashMap<>();
        models.values().forEach(md -> md.measures().forEach(ms -> m.putIfAbsent(ms.id(), new MeasureRef(md, ms))));
        this.measures = Collections.unmodifiableMap(m);
        this.fingerprint = fingerprint;
    }

    // ------------------------------------------------------------------ lookups
    public Map<String, Dimension> dimensions() { return dimensions; }
    public Map<String, Model> models() { return models; }
    public Map<String, DerivedMetric> derived() { return derived; }
    public Map<String, MeasureRef> measures() { return measures; }

    public Dimension dimension(String id) { return dimensions.get(id); }
    public Model model(String id) { return models.get(id); }
    public MeasureRef measure(String id) { return measures.get(id); }
    public DerivedMetric derivedMetric(String id) { return derived.get(id); }

    /** True for a measure or a derived metric id. */
    public boolean isMetric(String id) { return measures.containsKey(id) || derived.containsKey(id); }

    /** The entity model (clients), whose dimensions every client-owned model inherits. */
    public Model entityModel() { return models.values().stream().filter(Model::entityModel).findFirst().orElse(null); }

    /** SHA-256 of every semantic resource: changes whenever any definition changes (drives revalidation). */
    public String fingerprint() { return fingerprint; }

    // ------------------------------------------------------------------ loading
    /** Loads resources/semantic from the classpath and checks its structure; throws IllegalStateException on errors. */
    public static SemanticModel fromClasspath(ObjectMapper mapper) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource dims = resolver.getResource("classpath:" + RESOURCE_ROOT + "dimensions.json");
            Resource metrics = resolver.getResource("classpath:" + RESOURCE_ROOT + "metrics.json");
            Resource[] modelFiles = resolver.getResources("classpath:" + RESOURCE_ROOT + "models/*.json");
            Arrays.sort(modelFiles, Comparator.comparing(Resource::getFilename));
            Map<String, byte[]> bytes = new TreeMap<>();
            bytes.put("dimensions.json", read(dims));
            bytes.put("metrics.json", read(metrics));
            for (Resource r : modelFiles) bytes.put("models/" + r.getFilename(), read(r));
            List<JsonNode> modelJson = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : bytes.entrySet())
                if (e.getKey().startsWith("models/")) modelJson.add(mapper.readTree(e.getValue()));
            SemanticModel sm = parse(mapper, mapper.readTree(bytes.get("dimensions.json")), mapper.readTree(bytes.get("metrics.json")),
                    modelJson, sha256(bytes));
            List<String> problems = SemanticModelValidator.structural(sm);
            if (!problems.isEmpty())
                throw new IllegalStateException("the semantic model has " + problems.size() + " problem(s):\n- " + String.join("\n- ", problems));
            return sm;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the semantic model", e);
        }
    }

    static SemanticModel parse(ObjectMapper mapper, JsonNode dimsJson, JsonNode metricsJson, List<JsonNode> modelJson, String fingerprint) {
        Map<String, Dimension> dims = new LinkedHashMap<>();
        for (JsonNode d : dimsJson.path("dimensions")) {
            Dimension dim = mapper.convertValue(d, Dimension.class);
            if (dims.put(dim.id(), dim) != null) throw new IllegalStateException("dimension '" + dim.id() + "' is defined twice");
        }
        Map<String, Model> models = new LinkedHashMap<>();
        for (JsonNode m : modelJson) {
            Model model = parseModel(mapper, m);
            if (models.put(model.id(), model) != null) throw new IllegalStateException("model '" + model.id() + "' is defined twice");
        }
        models = inheritEntity(models);
        models.replaceAll((id, m) -> withDefaultSources(m));
        Map<String, DerivedMetric> derived = new LinkedHashMap<>();
        for (JsonNode d : metricsJson.path("metrics")) {
            DerivedMetric dm = parseDerived(mapper, d);
            if (derived.put(dm.id(), dm) != null) throw new IllegalStateException("metric '" + dm.id() + "' is defined twice");
        }
        return new SemanticModel(dims, models, derived, fingerprint);
    }

    private static Model parseModel(ObjectMapper mapper, JsonNode m) {
        String id = m.path("id").asText();
        String node = m.path("node").asText();
        Model.TimeSpec time = m.hasNonNull("time")
                ? new Model.TimeSpec(m.path("time").path("field").asText(), m.path("time").path("grain").asText("month"),
                m.path("time").path("closedOnly").asBoolean(false))
                : null;
        Map<String, String> dates = new LinkedHashMap<>();
        m.path("dates").fields().forEachRemaining(e -> dates.put(e.getKey(), e.getValue().asText()));
        Map<String, Model.Join> joins = new LinkedHashMap<>();
        m.path("joins").fields().forEachRemaining(e -> joins.put(e.getKey(), join(e.getValue())));
        Model.Join entityJoin = m.hasNonNull("entityJoin") ? join(m.get("entityJoin")) : null;
        Map<String, Model.Binding> bindings = new LinkedHashMap<>();
        m.path("dimensions").fields().forEachRemaining(e -> bindings.put(e.getKey(), e.getValue().isTextual()
                ? new Model.Binding(e.getValue().asText(), null)
                : new Model.Binding(e.getValue().path("field").asText(), text(e.getValue(), "display"))));
        Map<String, Model.Segment> segments = new LinkedHashMap<>();
        m.path("segments").fields().forEachRemaining(e -> segments.put(e.getKey(), new Model.Segment(
                e.getValue().path("description").asText(e.getKey()), conditions(e.getValue().path("where")))));
        List<Model.Measure> measures = new ArrayList<>();
        for (JsonNode ms : m.path("measures")) {
            String field = text(ms, "field");
            measures.add(new Model.Measure(ms.path("id").asText(), ms.path("label").asText(ms.path("id").asText()), ms.path("agg").asText(),
                    field, ms.hasNonNull("expr") ? ms.get("expr") : null, strings(ms.path("sources")),
                    strings(ms.path("segments")), ms.path("unit").asText("none"), ms.path("polarity").asText("neutral"),
                    ms.path("description").asText(""), ms.path("version").asInt(1)));
        }
        Model.Records records = null;
        if (m.hasNonNull("records")) {
            JsonNode r = m.get("records");
            List<Model.RecordField> fields = new ArrayList<>();
            for (JsonNode f : r.path("fields")) {
                String field = text(f, "field");
                Model.UnitFrom uf = f.hasNonNull("unitFrom") ? new Model.UnitFrom(f.get("unitFrom").path("field").asText(),
                        mapper.convertValue(f.get("unitFrom").path("map"), mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class)))
                        : null;
                fields.add(new Model.RecordField(f.path("id").asText(), f.path("label").asText(f.path("id").asText()), field,
                        f.hasNonNull("expr") ? f.get("expr") : null, strings(f.path("sources")), f.path("type").asText("string"),
                        text(f, "unit"), text(f, "dimension"), uf, text(f, "description")));
            }
            List<Model.SortKey> sort = new ArrayList<>();
            r.path("defaultSort").forEach(s -> sort.add(new Model.SortKey(s.path("by").asText(), s.path("dir").asText("asc"))));
            records = new Model.Records(r.path("key").asText(), List.copyOf(fields), List.copyOf(sort));
        }
        return new Model(id, m.path("label").asText(id), m.path("version").asInt(1), m.path("description").asText(""), node,
                text(m, "entity"), m.path("entityModel").asBoolean(false), entityJoin, time, dates, joins, bindings, segments,
                List.copyOf(measures), records);
    }

    /** {rel, dir, node} for one hop, or {path: [{rel, dir, node}, ...]} for several. */
    private static Model.Join join(JsonNode j) {
        if (j.has("path")) {
            List<Model.Hop> hops = new ArrayList<>();
            j.get("path").forEach(h -> hops.add(new Model.Hop(h.path("rel").asText(), h.path("dir").asText("out"), text(h, "node"))));
            return new Model.Join(List.copyOf(hops));
        }
        return Model.Join.of(j.path("rel").asText(), j.path("dir").asText("out"), text(j, "node"));
    }

    /**
     * Every client-owned model gets a many-to-one join to the entity model, named after the entity dimension, through
     * its {@code entityJoin} relationship, and every entity dimension it does not bind itself (client shown by name,
     * region, industry, ...).
     */
    private static Map<String, Model> inheritEntity(Map<String, Model> models) {
        Model entity = models.values().stream().filter(Model::entityModel).findFirst().orElse(null);
        if (entity == null) return models;
        String joinName = entity.dimensions().entrySet().stream().filter(e -> e.getValue().field().equals(entity.entity()))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (joinName == null) return models;
        Map<String, Model> out = new LinkedHashMap<>();
        for (Model m : models.values()) {
            if (m.entityModel() || m.entity() == null || m.entityJoin() == null) { out.put(m.id(), m); continue; }
            Map<String, Model.Join> joins = new LinkedHashMap<>(m.joins());
            joins.putIfAbsent(joinName, Model.Join.of(m.entityJoin().rel(), m.entityJoin().dir(), entity.node()));
            Map<String, Model.Binding> bindings = new LinkedHashMap<>(m.dimensions());
            entity.dimensions().forEach((dim, b) -> bindings.putIfAbsent(dim, b.field().equals(entity.entity())
                    ? new Model.Binding(m.entity(), b.display() == null ? null : joinName + "." + b.display())
                    : new Model.Binding(joinName + "." + b.field(), b.display() == null ? null : joinName + "." + b.display())));
            out.put(m.id(), new Model(m.id(), m.label(), m.version(), m.description(), m.node(), m.entity(), false, m.entityJoin(),
                    m.time(), m.dates(), joins, bindings, m.segments(), m.measures(), m.records()));
        }
        return out;
    }

    private static DerivedMetric parseDerived(ObjectMapper mapper, JsonNode d) {
        return new DerivedMetric(d.path("id").asText(), d.path("label").asText(d.path("id").asText()), d.path("kind").asText(),
                operand(mapper, d.get("numerator")), operand(mapper, d.get("denominator")), operand(mapper, d.get("left")),
                operand(mapper, d.get("right")), operand(mapper, d.get("of")), text(d, "compare"), d.path("unit").asText("none"),
                d.path("polarity").asText("neutral"), d.path("description").asText(""), d.path("version").asInt(1));
    }

    private static DerivedMetric.Operand operand(ObjectMapper mapper, JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (n.isTextual()) return new DerivedMetric.Ref(n.asText(), null);
        if (n.has("sum")) {
            List<DerivedMetric.Operand> parts = new ArrayList<>();
            n.get("sum").forEach(p -> parts.add(operand(mapper, p)));
            return new DerivedMetric.Sum(List.copyOf(parts));
        }
        return new DerivedMetric.Ref(n.path("metric").asText(), n.hasNonNull("window") ? mapper.convertValue(n.get("window"), Window.class) : null);
    }

    /**
     * Fills each measure's and record field's catalog references (for lineage) from its field when none are given:
     * the property itself ({@code Label.property}), or through a join the joined label's. Runs after joins are complete.
     */
    private static Model withDefaultSources(Model m) {
        List<Model.Measure> measures = m.measures().stream().map(ms -> ms.sources().isEmpty() && ms.field() != null
                ? new Model.Measure(ms.id(), ms.label(), ms.agg(), ms.field(), ms.expr(), List.of(source(m, ms.field())), ms.segments(),
                ms.unit(), ms.polarity(), ms.description(), ms.version()) : ms).toList();
        Model.Records records = m.records() == null ? null : new Model.Records(m.records().key(), m.records().fields().stream()
                .map(f -> f.sources().isEmpty() && f.field() != null
                        ? new Model.RecordField(f.id(), f.label(), f.field(), f.expr(), List.of(source(m, f.field())), f.type(), f.unit(),
                        f.dimension(), f.unitFrom(), f.description()) : f).toList(), m.records().defaultSort());
        return new Model(m.id(), m.label(), m.version(), m.description(), m.node(), m.entity(), m.entityModel(), m.entityJoin(),
                m.time(), m.dates(), m.joins(), m.dimensions(), m.segments(), measures, records);
    }

    private static String source(Model m, String field) {
        int dot = field.indexOf('.');
        if (dot > 0 && m.joins().containsKey(field.substring(0, dot)))
            return m.joins().get(field.substring(0, dot)).node() + "." + field.substring(dot + 1);
        return m.node() + "." + field;
    }

    private static List<Condition> conditions(JsonNode where) {
        List<Condition> out = new ArrayList<>();
        where.forEach(c -> out.add(new Condition(c.path("field").asText(), c.path("op").asText(), c.get("value"))));
        return List.copyOf(out);
    }

    static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) arr.forEach(s -> out.add(s.asText()));
        return List.copyOf(out);
    }

    private static String text(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }

    private static byte[] read(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) { return in.readAllBytes(); }
    }

    private static String sha256(Map<String, byte[]> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            files.forEach((name, b) -> { md.update(name.getBytes(StandardCharsets.UTF_8)); md.update(b); });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
