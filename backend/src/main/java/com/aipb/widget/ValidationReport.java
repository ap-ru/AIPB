package com.aipb.widget;

import com.aipb.semantic.ResultSet;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The outcome of validating a widget spec. Errors block saving; warnings do not. Each issue names a stable code, the
 * part of the spec it concerns and a message precise enough to act on - for a person in the workbench now, and for
 * the assistant later. {@code preview} holds the first rows of a successful live run.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationReport(boolean ok, List<Issue> errors, List<Issue> warnings, Map<String, String> sampleParams,
                               Preview preview) {

    public record Issue(String code, String path, String message) {}

    /**
     * The first rows of the live run, with the columns as the widget will show them; {@code notes} from the semantic
     * layer; {@code narrative}: what an insight says for the sample.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Preview(List<ResultSet.Column> columns, JsonNode rows, int count, Integer total, boolean truncated,
                          String asOfDate, List<String> notes, NarrativeRenderer.Narrative narrative) {
        public Preview(List<ResultSet.Column> columns, JsonNode rows, int count, Integer total, boolean truncated, String asOfDate, List<String> notes) {
            this(columns, rows, count, total, truncated, asOfDate, notes, null);
        }
    }
}
