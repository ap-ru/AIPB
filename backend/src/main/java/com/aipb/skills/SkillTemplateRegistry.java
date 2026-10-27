package com.aipb.skills;

import com.aipb.model.Layout;
import com.aipb.widget.WidgetPrimitives;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;

/**
 * The Skills Studio catalog: every template in resources/skills/templates, loaded and checked once at startup. A bad
 * template stops the app - the schema, a duplicate id, an unknown kind, a guidance key that is not a stage of its
 * kind, an unknown primitive or worked example, or handlers on a kind that has none. Collections are checked against
 * the data dictionary by SkillTemplatesTest, since the dictionary is only reachable once the source is up.
 */
@Component
public class SkillTemplateRegistry {

    private final Map<String, SkillTemplate> templates = new LinkedHashMap<>();
    private final Map<String, SkillKind> kinds = new LinkedHashMap<>();

    @Autowired
    public SkillTemplateRegistry(List<SkillKind> kinds, WidgetPrimitives primitives, ObjectMapper mapper) {
        this(kinds, primitives, mapper, "classpath:skills/templates/*.json");
    }

    SkillTemplateRegistry(List<SkillKind> kinds, WidgetPrimitives primitives, ObjectMapper mapper, String pattern) {
        kinds.forEach(k -> this.kinds.put(k.id(), k));
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(read(mapper, new ClassPathResource("skills/skill-template.schema.json")));
        List<String> errors = new ArrayList<>();
        List<SkillTemplate> loaded = new ArrayList<>();
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(pattern);
            Arrays.sort(files, Comparator.comparing(Resource::getFilename));
            for (Resource r : files) {
                JsonNode json = read(mapper, r);
                Set<ValidationMessage> msgs = schema.validate(json);
                if (!msgs.isEmpty()) {
                    msgs.forEach(m -> errors.add(r.getFilename() + ": " + m.getMessage()));
                    continue;
                }
                SkillTemplate t = mapper.treeToValue(json, SkillTemplate.class);
                if (!r.getFilename().equals(t.id() + ".json")) errors.add(r.getFilename() + ": the file name must be the template id (" + t.id() + ".json)");
                loaded.add(t);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the skill templates", e);
        }
        for (SkillTemplate t : loaded) {
            if (templates.putIfAbsent(t.id(), t) != null) errors.add(t.id() + ": duplicate template id");
            errors.addAll(check(t, primitives).stream().map(e -> t.id() + ": " + e).toList());
        }
        if (!templates.containsKey(SkillTemplate.CUSTOM_WIDGET)) errors.add("the " + SkillTemplate.CUSTOM_WIDGET + " template is required (free-form requests)");
        if (!errors.isEmpty()) throw new IllegalStateException("invalid skill templates:\n- " + String.join("\n- ", errors));
    }

    /** Reference checks the schema cannot make. */
    List<String> check(SkillTemplate t, WidgetPrimitives primitives) {
        List<String> errors = new ArrayList<>();
        SkillKind kind = kinds.get(t.kind());
        if (kind == null) return List.of("unknown kind '" + t.kind() + "'; known: " + String.join(", ", kinds.keySet()));
        Set<String> stages = new LinkedHashSet<>(List.of("intake"));
        stages.addAll(kind.stageLabels().keySet());
        t.guidance().keySet().stream().filter(s -> !stages.contains(s))
                .forEach(s -> errors.add("guidance for '" + s + "', which is not a stage of a " + t.kind() + " (" + String.join(", ", stages) + ")"));
        t.examples().forEach((stage, ids) -> {
            if (!stages.contains(stage)) errors.add("examples for '" + stage + "', which is not a stage of a " + t.kind());
            ids.stream().filter(id -> !new ClassPathResource("assistant/examples/" + id + ".json").exists())
                    .forEach(id -> errors.add("unknown worked example '" + id + "' (resources/assistant/examples)"));
        });
        t.constraints().primitives().stream().filter(p -> primitives.primitive(p).isMissingNode())
                .forEach(p -> errors.add("unknown primitive '" + p + "'"));
        Object display = t.defaults().brief().get("display");
        if (display != null && !t.constraints().primitives().isEmpty() && !t.constraints().primitives().contains(display.toString()))
            errors.add("the default display '" + display + "' is not one of the allowed primitives");
        if (!t.constraints().handlers().isEmpty() && !kind.hasHandlers()) errors.add("a " + t.kind() + " has no action handlers");
        String scope = t.constraints().scope();
        if (scope != null && !t.pageScopes().contains(scope)) errors.add("scope '" + scope + "' is not one of its pageScopes");
        Set<String> names = new HashSet<>();
        for (SkillTemplate.Input in : t.inputs()) {
            if (!names.add(in.name())) errors.add("duplicate input '" + in.name() + "'");
            if ("enum".equals(in.type()) && (in.options() == null || in.options().isEmpty())) errors.add("input '" + in.name() + "' is an enum without options");
        }
        return errors;
    }

    public List<SkillTemplate> all() { return List.copyOf(templates.values()); }

    /** The templates that fit a page (by whether it supplies a client). */
    public List<SkillTemplate> forPage(Layout.Page page) { return templates.values().stream().filter(t -> t.fits(page)).toList(); }

    public SkillTemplate get(String id) {
        SkillTemplate t = templates.get(id);
        if (t == null) throw new NoSuchElementException("skill template " + id);
        return t;
    }

    /** A session's template; sessions started before templates existed are Custom widget runs. */
    public SkillTemplate forSession(String templateId) { return get(templateId == null ? SkillTemplate.CUSTOM_WIDGET : templateId); }

    public SkillKind kind(SkillTemplate t) { return kinds.get(t.kind()); }

    /**
     * The Studio form's values for a new session: only declared inputs, every required one present, enum values from
     * their options. Blank values are dropped.
     */
    public Map<String, String> checkInputs(SkillTemplate t, Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> in = values == null ? Map.of() : values;
        Set<String> declared = new HashSet<>();
        for (SkillTemplate.Input i : t.inputs()) {
            declared.add(i.name());
            String v = in.get(i.name());
            if (v == null || v.isBlank()) {
                if (i.isRequired()) throw new IllegalArgumentException(i.label() + " is required for " + t.title());
                continue;
            }
            if ("enum".equals(i.type()) && !i.options().contains(v.trim()))
                throw new IllegalArgumentException(i.label() + " must be one of: " + String.join(", ", i.options()));
            if ("number".equals(i.type())) {
                try { Double.parseDouble(v.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(i.label() + " must be a number"); }
            }
            out.put(i.name(), v.trim());
        }
        in.keySet().stream().filter(k -> !declared.contains(k)).findFirst()
                .ifPresent(k -> { throw new IllegalArgumentException(t.title() + " has no input '" + k + "'"); });
        return out;
    }

    private static JsonNode read(ObjectMapper mapper, Resource r) {
        try (InputStream in = r.getInputStream()) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + r.getFilename(), e);
        }
    }
}
