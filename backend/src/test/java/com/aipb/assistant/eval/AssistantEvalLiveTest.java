package com.aipb.assistant.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant's evaluation: each golden request (golden/cases.json) is sent to the real assistant through the
 * running backend, as a banker would, and the proposed widget's numbers are scored against the verified starter
 * widget for the same request and client ({@link EvalScorer}). Writes target/assistant-eval/report.md with the
 * pass rate, cost and time per case.
 *
 * <p><b>This calls Claude and costs money</b> (roughly $0.35-0.45 per case at medium effort on Opus 5.5). It never runs
 * in the normal build. With the stack up and ANTHROPIC_API_KEY set:
 * <pre>mvn test -Dgroups=eval -Dsurefire.excludedGroups=                    (all 12 cases)
 * mvn test -Dgroups=eval -Dsurefire.excludedGroups= -Daipb.eval.limit=3  (the first 3)
 * mvn test -Dgroups=eval -Dsurefire.excludedGroups= -Daipb.eval.only=client-exposure,client-revenue-trend</pre>
 * Clarifying questions get one generic reply ("use your defaults"); the review is never requested. Each request asks for
 * a new widget (the golden widgets already exist); {@code -Daipb.eval.allowReuse=true} sends the bare request instead.
 */
@Tag("eval")
class AssistantEvalLiveTest {

    private static final String BASE = System.getProperty("aipb.url", "http://localhost:8080");
    private static final String CLIENT = System.getProperty("aipb.eval.client", "CL-0001");
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    record CaseResult(String id, String request, boolean pass, String reason, String outcome, double costUsd, long ms) {}

    @Test
    void goldenRequestsProduceTheVerifiedNumbers() throws Exception {
        JsonNode status = get("/api/assistant/status");
        assertThat(status.path("enabled").asBoolean()).as("assistant enabled (ANTHROPIC_API_KEY set on the backend)").isTrue();
        List<JsonNode> cases = new ArrayList<>();
        mapper.readTree(new ClassPathResource("golden/cases.json").getInputStream()).forEach(cases::add);
        String only = System.getProperty("aipb.eval.only");
        if (only != null) cases.removeIf(c -> !Set.of(only.split(",")).contains(c.path("definitionId").asText()));
        int limit = Integer.getInteger("aipb.eval.limit", cases.size());
        List<CaseResult> results = new ArrayList<>();
        for (JsonNode c : cases.subList(0, Math.min(limit, cases.size()))) results.add(runCase(c));
        writeReport(results, status);
        assertThat(results).isNotEmpty();
    }

    private CaseResult runCase(JsonNode c) throws Exception {
        String id = c.path("definitionId").asText();
        // the golden widgets are already on their pages (and in the library), so a bare request is rightly answered with
        // "already on this page" or a reuse; the eval measures building, so it asks for a new widget explicitly
        String request = c.path("request").asText() + (Boolean.parseBoolean(System.getProperty("aipb.eval.allowReuse", "false"))
                ? "" : " Build it as a new widget; don't point me to or reuse an existing one.");
        boolean book = "book".equals(c.path("scope").asText());
        Map<String, String> params = book ? Map.of() : Map.of("clientId", CLIENT);
        long t0 = System.currentTimeMillis();

        ObjectNode create = mapper.createObjectNode().put("layoutId", "default").put("pageId", book ? "command-center" : "client");
        create.set("params", mapper.valueToTree(params));
        String session = post("/api/assistant/sessions", create).path("id").asText();
        List<String> events = message(session, request, params);
        JsonNode s = get("/api/assistant/sessions/" + session);
        if (s.path("proposal").isNull() && "awaiting_user".equals(s.path("status").asText())) {
            events.addAll(message(session, "Use your defaults for anything I didn't specify.", params));
            s = get("/api/assistant/sessions/" + session);
        }
        long ms = System.currentTimeMillis() - t0;
        double cost = s.path("costUsd").asDouble();
        JsonNode proposal = s.path("proposal");
        if (proposal.isNull() || proposal.isMissingNode()) {
            JsonNode last = s.path("turns").get(s.path("turns").size() - 1);
            return new CaseResult(id, request, false, "no proposal: " + last.path("text").asText(), s.path("status").asText(), cost, ms);
        }
        JsonNode expected = get("/api/widgets/library/" + id + "/data" + query(params));
        EvalScorer.Score score = EvalScorer.score(expected.path("view"), expected.path("rows"), proposal.path("preview").path("rows"));
        String outcome = proposal.path("kind").asText() + " " + proposal.path("primitive").asText() + " \"" + proposal.path("title").asText() + "\"";
        return new CaseResult(id, request, score.pass(), score.reason(), outcome, cost, ms);
    }

    // ------------------------------------------------------------------ http
    // the assistant is for roles that build widgets
    private static final String ROLE = "X-AIPB-Role";
    private static final String BUILDER = "builder";

    private List<String> message(String session, String text, Map<String, String> params) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("text", text);
        body.set("params", mapper.valueToTree(params));
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + "/api/assistant/sessions/" + session + "/messages"))
                .timeout(Duration.ofMinutes(8)).header("Content-Type", "application/json").header("Accept", "text/event-stream").header(ROLE, BUILDER)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        List<String> events = new ArrayList<>();
        try (Stream<String> lines = http.send(req, HttpResponse.BodyHandlers.ofLines()).body()) {
            lines.filter(l -> l.startsWith("event:")).forEach(l -> events.add(l.substring(6).trim()));
        }
        return events;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(60)).header(ROLE, BUILDER).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(r.body());
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").header(ROLE, BUILDER).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(r.body());
    }

    private static String query(Map<String, String> params) {
        if (params.isEmpty()) return "";
        StringJoiner j = new StringJoiner("&", "?", "");
        params.forEach((k, v) -> j.add(k + "=" + v));
        return j.toString();
    }

    private void writeReport(List<CaseResult> results, JsonNode status) throws Exception {
        long passed = results.stream().filter(CaseResult::pass).count();
        double cost = results.stream().mapToDouble(CaseResult::costUsd).sum();
        StringBuilder md = new StringBuilder("# Assistant evaluation\n\n")
                .append("Model ").append(status.path("model")).append(", effort ").append(status.path("effort")).append(". Client ").append(CLIENT).append(".\n\n")
                .append("**").append(passed).append(" / ").append(results.size()).append(" passed** · total cost $").append(String.format("%.2f", cost))
                .append(" · ").append(String.format("%.0f", results.stream().mapToLong(CaseResult::ms).average().orElse(0) / 1000)).append(" s average\n\n")
                .append("| case | request | result | proposal | cost | time |\n|---|---|---|---|---|---|\n");
        for (CaseResult r : results) md.append("| ").append(r.id()).append(" | ").append(r.request()).append(" | ").append(r.pass() ? "PASS" : "FAIL: " + r.reason())
                .append(" | ").append(r.outcome()).append(" | $").append(String.format("%.3f", r.costUsd())).append(" | ").append(r.ms() / 1000).append(" s |\n");
        Path dir = Path.of("target/assistant-eval");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("report.md"), md.toString());
        System.out.println(md);
    }
}
