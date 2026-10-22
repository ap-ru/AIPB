package com.aipb.semantic;

import java.util.List;

/** A query the semantic layer cannot answer as asked; each issue says what to change. */
public class SemanticQueryException extends RuntimeException {

    /** {@code path} is the part of the query concerned, e.g. metrics[1] or window. */
    public record Issue(String code, String path, String message) {}

    private final transient List<Issue> issues;

    public SemanticQueryException(List<Issue> issues) {
        super(issues.size() == 1 ? issues.get(0).message() : issues.size() + " problems: "
                + String.join("; ", issues.stream().map(Issue::message).toList()));
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() { return issues; }
}
