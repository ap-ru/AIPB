package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** McpClient + SourceData against a fake MCP server speaking the Streamable HTTP transport (JSON responses). */
class SourceDataTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private volatile String liveSession;
    private volatile JsonNode nextResult;
    private HttpServer server;
    private SourceData source;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", ex -> {
            JsonNode msg = mapper.readTree(ex.getRequestBody());
            String method = msg.path("method").asText();
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if ("initialize".equals(method)) {
                liveSession = "s" + sessions.incrementAndGet();
                ex.getResponseHeaders().add("mcp-session-id", liveSession);
                reply(ex, 200, rpc(msg, mapper.createObjectNode().set("serverInfo",
                        mapper.createObjectNode().put("name", "Fake MCP").put("version", "1"))));
                return;
            }
            if (sid == null || !sid.equals(liveSession)) { reply(ex, 404, "{}"); return; }
            if (method.startsWith("notifications/")) { reply(ex, 202, ""); return; }
            calls.add(msg);
            reply(ex, 200, rpc(msg, nextResult));
        });
        server.start();
        McpClient mcp = new McpClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", 5);
        source = new SourceData(mcp, "bank");
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void aggregatePinsTheDatabaseAndReturnsPlainJson() {
        nextResult = documents("[{\"_id\":\"HL-1\",\"openedDate\":{\"$date\":\"2021-06-01T00:00:00.000Z\"},"
                + "\"drawnUsd\":{\"$numberLong\":\"178400000\"}}]", "[]");

        SourceData.Result r = source.aggregate("holdings", List.of(Map.of("$match", Map.of("clientId", "CL-0001"))));

        JsonNode args = calls.get(0).path("params").path("arguments");
        assertThat(calls.get(0).path("params").path("name").asText()).isEqualTo("aggregate");
        assertThat(args.path("database").asText()).isEqualTo("bank");
        assertThat(args.path("connectionId").asText()).isEqualTo("preconfigured");
        assertThat(args.path("pipeline").get(0).path("$match").path("clientId").asText()).isEqualTo("CL-0001");
        assertThat(r.count()).isEqualTo(1);
        assertThat(r.truncated()).isFalse();
        assertThat(r.documents().get(0).path("openedDate").asText()).isEqualTo("2021-06-01T00:00:00.000Z");
        assertThat(r.documents().get(0).path("drawnUsd").asLong()).isEqualTo(178_400_000L);
    }

    @Test
    void resultCutShortByAServerLimitIsMarkedTruncated() {
        nextResult = documents("[{\"a\":1}]", "[\"maxDocumentsPerQuery\"]");
        ((ObjectNode) nextResult.get("structuredContent")).put("queryResultsCount", 6159);
        SourceData.Result r = source.find("clients", null, null, null, null);
        assertThat(r.truncated()).isTrue();
        assertThat(r.count()).isEqualTo(1);
        assertThat(r.total()).isEqualTo(6159);
        assertThat(r.limits()).containsExactly("maxDocumentsPerQuery");
    }

    @Test
    void expiredSessionIsReopenedTransparently() {
        nextResult = documents("[]", "[]");
        source.find("clients", null, null, null, 1);
        liveSession = "gone"; // the server forgets the session
        source.find("clients", null, null, null, 1);
        assertThat(sessions.get()).isEqualTo(2);
        assertThat(calls).hasSize(2);
    }

    @Test
    void toolErrorsSurfaceTheServersReason() {
        ObjectNode err = mapper.createObjectNode().put("isError", true);
        err.putArray("content").addObject().put("type", "text").put("text", "In readOnly mode you can not run pipelines with $out");
        nextResult = err;
        assertThatThrownBy(() -> source.aggregate("clients", List.of(Map.of("$out", "x"))))
                .isInstanceOf(SourceException.class).hasMessageContaining("readOnly");
    }

    @Test
    void invalidCollectionNamesNeverReachTheServer() {
        assertThatThrownBy(() -> source.find("system.users", null, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.find("a b", null, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void unreachableServerIsASourceException() {
        server.stop(0);
        assertThatThrownBy(() -> source.collections()).isInstanceOf(SourceException.class).hasMessageContaining("unreachable");
    }

    // ------------------------------------------------------------------ helpers
    private JsonNode documents(String docs, String limits) {
        try {
            ObjectNode r = mapper.createObjectNode();
            ObjectNode sc = r.putObject("structuredContent");
            sc.set("documents", mapper.readTree(docs));
            sc.set("appliedLimits", mapper.readTree(limits));
            r.putArray("content").addObject().put("type", "text").put("text", "ok");
            return r;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String rpc(JsonNode request, JsonNode result) {
        ObjectNode m = mapper.createObjectNode().put("jsonrpc", "2.0");
        m.set("id", request.get("id"));
        m.set("result", result);
        return m.toString();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
