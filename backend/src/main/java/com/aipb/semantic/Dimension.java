package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A conformed dimension: a way to break down or filter figures that means the same in every model binding it. A
 * dimension with {@code displayColumn} is a key shown by a label (client: clientId shown as clientName). A dimension
 * with {@code grain} is a time dimension over a model's time field. {@code tones} say which values are good or bad.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Dimension(String id, String label, String type, String description, String displayColumn, String displayLabel,
                        String grain, List<String> values, Map<String, String> tones) {

    public boolean isTime() { return grain != null; }
}
