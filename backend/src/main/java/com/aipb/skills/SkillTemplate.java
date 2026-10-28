package com.aipb.skills;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aipb.model.Layout;

import java.util.List;
import java.util.Map;

/**
 * A Skills Studio template (resources/skills/templates/*.json): what the Studio shows, and what guides (stage guidance,
 * worked examples, brief defaults) and constrains (scope, primitives, collections, rows) one assistant run. Templates
 * are written by developers, versioned in git and checked at startup by {@link SkillTemplateRegistry}.
 */
public record SkillTemplate(String id, int version, String kind, String title, String summary, String icon, String category,
                            List<String> pageScopes, List<Input> inputs, Map<String, String> guidance, Constraints constraints,
                            Defaults defaults, Map<String, List<String>> examples, List<String> suggestions) {

    public static final String CUSTOM_WIDGET = "custom-widget";

    public SkillTemplate {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        guidance = guidance == null ? Map.of() : Map.copyOf(guidance);
        constraints = constraints == null ? Constraints.NONE : constraints;
        defaults = defaults == null ? new Defaults(null) : defaults;
        examples = examples == null ? Map.of() : Map.copyOf(examples);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** A field the Studio asks for before the conversation starts. */
    public record Input(String name, String label, String type, List<String> options, Boolean required, String help) {
        @JsonIgnore
        public boolean isRequired() { return Boolean.TRUE.equals(required); }
    }

    /** What the run may produce. An empty list or null means unconstrained. */
    public record Constraints(List<String> primitives, List<String> collections, List<String> handlers, String scope,
                              Boolean requireReview, Integer maxRows) {
        static final Constraints NONE = new Constraints(null, null, null, null, null, null);

        public Constraints {
            primitives = primitives == null ? List.of() : List.copyOf(primitives);
            collections = collections == null ? List.of() : List.copyOf(collections);
            handlers = handlers == null ? List.of() : List.copyOf(handlers);
        }

        @JsonIgnore
        public boolean isEmpty() {
            return primitives.isEmpty() && collections.isEmpty() && handlers.isEmpty() && scope == null
                    && !Boolean.TRUE.equals(requireReview) && maxRows == null;
        }
    }

    /** Brief fields applied where Intake left them open. */
    public record Defaults(Map<String, Object> brief) {
        public Defaults { brief = brief == null ? Map.of() : Map.copyOf(brief); }
    }

    /** The page's scope for templates: client when it supplies clientId, book otherwise. */
    public static String pageScope(Layout.Page page) {
        return page != null && page.param("clientId") != null ? "client" : "book";
    }

    public boolean fits(Layout.Page page) { return pageScopes.contains(pageScope(page)); }

    /** Guidance for one stage, or null. */
    public String guidance(String stage) { return guidance.get(stage); }

    /** Worked-example ids replacing a stage's defaults, or null to keep them. */
    public List<String> examples(String stage) { return examples.get(stage); }

    /** True when the template adds nothing to a stage's system prompt (the Custom widget template, on every stage). */
    public boolean silentOn(String stage) { return guidance(stage) == null && constraints.isEmpty(); }
}
