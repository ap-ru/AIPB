package com.aipb.semantic;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A metric computed from other metrics, possibly across models: ratio (numerator / denominator), difference
 * (left - right), change ((current - prior) / prior over the compare window), or same (an operand under a new name,
 * typically with a pinned window). Operands refer to measures or other derived metrics by id.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivedMetric(String id, String label, String kind, Operand numerator, Operand denominator, Operand left,
                            Operand right, Operand of, String compare, String unit, String polarity, String description,
                            int version) {

    /** A metric reference, optionally with a pinned window, or a sum of operands. */
    public sealed interface Operand permits Ref, Sum {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ref(String metric, Window window) implements Operand {}

    public record Sum(List<Operand> of) implements Operand {}

    public List<Operand> operands() {
        return java.util.stream.Stream.of(numerator, denominator, left, right, of).filter(java.util.Objects::nonNull).toList();
    }
}
