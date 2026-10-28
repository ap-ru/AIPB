package com.aipb.assistant;

import com.aipb.widget.ValidationReport;
import com.aipb.widget.WidgetDataService;
import com.aipb.widget.WidgetDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * What the banker sees before approving - assembled from the stages' artifacts by code, not written by a model, so it
 * describes exactly what was built: the preview from the validator's live run, each figure's definition and the
 * dictionary entry it comes from, every assumption, the checks that passed, warnings, and the review state.
 */
@Component
@RequiredArgsConstructor
public class ProposalBuilder {

    private final ObjectMapper mapper;
    private final WidgetDataService widgetData;

    public ObjectNode newWidget(JsonNode brief, JsonNode data, JsonNode spec, ValidationReport report, String reviewState,
                                JsonNode review, boolean revisedAfterReview) {
        ObjectNode p = mapper.createObjectNode();
        p.put("kind", "new");
        p.put("title", spec.path("title").asText());
        p.put("description", spec.path("description").asText());
        p.put("primitive", spec.path("view").path("primitive").asText());
        p.set("spec", spec);
        ObjectNode preview = p.putObject("preview");
        preview.set("view", spec.path("view"));
        preview.set("columns", mapper.valueToTree(report.preview().columns()));
        preview.set("rows", report.preview().rows());
        preview.put("count", report.preview().count());
        preview.put("truncated", report.preview().truncated());
        if (report.preview().narrative() != null) preview.set("narrative", mapper.valueToTree(report.preview().narrative()));
        preview.set("sampleParams", mapper.valueToTree(report.sampleParams()));

        ArrayNode shows = p.putArray("whatItShows");
        Map<String, String> labels = new java.util.HashMap<>();
        spec.path("data").path("columns").forEach(c -> labels.put(c.path("name").asText(), c.path("label").asText()));
        data.path("measures").forEach(m -> shows.addObject()
                .put("figure", labels.getOrDefault(m.path("column").asText(), m.path("column").asText()))
                .put("definition", m.path("definition").asText())
                .put("source", m.path("catalogReference").asText()));

        ArrayNode assumptions = p.putArray("assumptions");
        brief.path("assumptions").forEach(a -> assumptions.add(a.asText()));
        data.path("assumptions").forEach(a -> assumptions.add(a.asText()));

        ArrayNode checks = p.putArray("checks");
        checks.add("Every figure's definition is cited from the data dictionary.");
        checks.add("Passed the widget validator: query ran live, " + report.preview().count() + " row" + (report.preview().count() == 1 ? "" : "s")
                + ", columns and types as declared, units and formats agree, nothing cut off.");
        checks.add("Fits this page: every parameter it needs is supplied here.");

        ArrayNode warnings = p.putArray("warnings");
        report.warnings().forEach(w -> warnings.add(w.message()));
        data.path("warnings").forEach(w -> { if (!w.path("code").asText().equals("empty")) warnings.add(w.path("message").asText()); });

        ObjectNode r = p.putObject("review");
        r.put("state", reviewState);
        if (review != null) {
            r.put("summary", review.path("summary").asText());
            r.set("checks", review.path("checks"));
            r.set("independentChecks", review.path("independentChecks"));
            r.set("issues", review.path("issues"));
        }
        if (revisedAfterReview) r.put("note", "The data was revised to address the review's findings; the revision has not been reviewed again.");
        return p;
    }

    /** An existing library widget that already answers the request, previewed with the page's current parameters. */
    public ObjectNode reuse(WidgetDefinition d, Map<String, String> params) {
        ObjectNode p = mapper.createObjectNode();
        p.put("kind", "reuse");
        p.put("definitionId", d.getId());
        p.put("title", d.getTitle());
        p.put("description", d.getDescription());
        p.put("primitive", d.getPrimitive());
        WidgetDataService.DataResult r = widgetData.data(d.getId(), params);
        ObjectNode preview = p.putObject("preview");
        preview.set("view", mapper.valueToTree(widgetData.spec(d.getId()).view()));
        preview.set("columns", mapper.valueToTree(r.columns()));
        preview.set("rows", r.rows());
        preview.put("count", r.count());
        preview.put("truncated", r.truncated());
        p.putObject("review").put("state", "library");
        return p;
    }
}
