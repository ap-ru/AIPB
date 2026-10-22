package com.aipb.assistant.gates;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a submit_review submission (the opt-in review). A "pass" must be earned: at least one independent
 * recomputation that matches, no failed check and no blocking issue. A pass without that is sent back.
 */
@Component
public class ReviewGate {

    /** {@code passed} is the verdict once the submission is accepted. */
    public record Result(boolean accepted, List<String> errors, boolean passed) {}

    public Result check(JsonNode in) {
        List<String> errors = new ArrayList<>();
        boolean pass = "pass".equals(in.path("verdict").asText());
        boolean matched = false;
        for (JsonNode c : in.path("independentChecks")) if (c.path("match").asBoolean(false)) matched = true;
        boolean blocking = false;
        for (JsonNode i : in.path("issues")) if ("blocking".equals(i.path("severity").asText())) blocking = true;
        boolean failedCheck = false;
        for (JsonNode c : in.path("checks")) if ("fail".equals(c.path("result").asText())) failedCheck = true;
        if (pass && !matched)
            errors.add("a pass needs at least one independent recomputation with match: true - recompute a key figure a different way with run_query, or return fail");
        if (pass && (blocking || failedCheck))
            errors.add("the verdict is pass but a check failed or an issue is blocking - return fail, or resolve the contradiction");
        if (!errors.isEmpty()) return new Result(false, errors, false);
        return new Result(true, List.of(), pass);
    }
}
