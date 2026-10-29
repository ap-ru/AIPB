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

/**
 * McpClient + GraphMcpSource against a fake Cypher MCP server speaking the Streamable HTTP transport (SSE responses,
 * as mcp-neo4j-cypher answers). Without a driver the EXPLAIN check is skipped; the text check still runs first.
 */
class GraphMcpSourceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger sessions = new AtomicInteger();
    private volatile boolean stateless;
    private volatile String liveSession;
    private volatile JsonNode nextResult;
    private HttpServer server;
    private GraphMcpSource source;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp/", ex -> {
            JsonNode msg = mapper.readTree(ex.getRequestBody());
            String method = msg.path("method").asText();
            String sid = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
            if ("initialize".equals(method)) {
                if (!stateless) {
                    liveSession = "s" + sessions.incrementAndGet();
                    ex.getResponseHeaders().add("mcp-session-id", liveSession);
                }
                reply(ex, 200, rpc(msg, mapper.createObjectNode().set("serverInfo",
                        mapper.createObjectNode().put("name", "mcp-neo4j-cypher").put("version", "2.13.3"))));
                return;
            }
            if (!stateless && (sid == null || !sid.equals(liveSession))) { reply(ex, 404, "{}"); return; }
            if (method.startsWith("notifications/")) { reply(ex, 202, ""); return; }
            calls.add(msg);
            reply(ex, 200, rpc(msg, nextResult));
        });
        server.start();
        McpClient mcp = new McpClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp/", 5);
        source = new GraphMcpSource(mcp, null, mapper, "neo4j", 3);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void cypherSendsTheQueryAndParametersAndReturnsPlainJson() {
        nextResult = rows("[{\"name\": \"Vantage Energy Corp\", \"n\": 5, \"since\": \"2018-08-25T00:00:00.000000000+00:00\"}]");

        SourceReader.Result r = source.cypher("MATCH (c:Client {clientId: $id})-[:HOLDS]->(h) RETURN c.name AS name, count(h) AS n, "
                + "c.relationshipSince AS since", Map.of("id", "CL-0001"));

        JsonNode call = calls.get(0).path("params");
        assertThat(call.path("name").asText()).isEqualTo("read_neo4j_cypher");
        assertThat(call.path("arguments").path("params").path("id").asText()).isEqualTo("CL-0001");
        assertThat(r.count()).isEqualTo(1);
        assertThat(r.truncated()).isFalse();
        assertThat(r.documents().get(0).path("since").asText()).isEqualTo("2018-08-25T00:00:00.000Z");
        assertThat(r.documents().get(0).path("n").asInt()).isEqualTo(5);
    }

    @Test
    void aStatelessServerNeedsNoSession() {
        stateless = true;
        nextResult = rows("[]");
        source.cypher("MATCH (c:Client) RETURN c.name AS name", Map.of());
        source.cypher("MATCH (c:Client) RETURN c.name AS name", Map.of());
        assertThat(calls).hasSize(2);
        assertThat(sessions.get()).isZero();
    }

    @Test
    void resultsLongerThanTheCapAreCutAndMarkedTruncated() {
        nextResult = rows("[{\"a\":1},{\"a\":2},{\"a\":3},{\"a\":4},{\"a\":5}]");
        SourceReader.Result r = source.cypher("MATCH (n:Holding) RETURN n.holdingId AS a", Map.of());
        assertThat(r.truncated()).isTrue();
        assertThat(r.count()).isEqualTo(3);
        assertThat(r.total()).isEqualTo(5);
        assertThat(r.limits()).containsExactly(SourceReader.MAX_RECORDS);
    }

    @Test
    void writesAreRefusedBeforeTheyReachTheServer() {
        assertThatThrownBy(() -> source.cypher("MATCH (c:Client) SET c.name = 'x'", Map.of())).isInstanceOf(SourceException.class)
                .hasMessageContaining("read-only").hasMessageContaining("SET");
        assertThatThrownBy(() -> source.cypher("CREATE INDEX x FOR (n:Client) ON (n.name)", Map.of())).isInstanceOf(SourceException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void findIsParameterizedCypherAndLeavesAbsentPropertiesOut() {
        nextResult = rows("[{\"n\": {\"_id\": \"CL-0001\", \"name\": \"Vantage Energy Corp\", \"segment\": null}}]");
        SourceReader.Result r = source.find("Client", Map.of("clientId", "CL-0001"), List.of("name", "segment"), Map.of("name", 1), 1);
        JsonNode args = calls.get(0).path("params").path("arguments");
        assertThat(args.path("query").asText()).isEqualTo("MATCH (n:Client) WHERE n.clientId = $f0 RETURN n {._id, .name, .segment} AS n ORDER BY n.name LIMIT $limit");
        assertThat(args.path("params").path("f0").asText()).isEqualTo("CL-0001");
        assertThat(r.documents().get(0).has("segment")).isFalse();
        assertThat(r.total()).isNull();
    }

    @Test
    void toolErrorsSurfaceTheServersReason() {
        ObjectNode err = mapper.createObjectNode().put("isError", true);
        err.putArray("content").addObject().put("type", "text").put("text", "Neo4j Error: Invalid input 'RETRUN'");
        nextResult = err;
        assertThatThrownBy(() -> source.cypher("MATCH (c:Client) RETRUN c", Map.of()))
                .isInstanceOf(SourceException.class).hasMessageContaining("Invalid input");
    }

    @Test
    void invalidLabelsNeverReachTheServer() {
        assertThatThrownBy(() -> source.find("Client) DETACH DELETE (n", null, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.find("Client", Map.of("a b", 1), null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void unreachableServerIsASourceException() {
        server.stop(0);
        assertThatThrownBy(() -> source.labels()).isInstanceOf(SourceException.class).hasMessageContaining("unreachable");
    }

    // ------------------------------------------------------------------ helpers
    private JsonNode rows(String json) {
        ObjectNode r = mapper.createObjectNode().put("isError", false);
        r.putArray("content").addObject().put("type", "text").put("text", json);
        return r;
    }

    private String rpc(JsonNode request, JsonNode result) {
        ObjectNode m = mapper.createObjectNode().put("jsonrpc", "2.0");
        m.set("id", request.get("id"));
        m.set("result", result);
        return "event: message\ndata: " + m + "\n\n";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", status == 200 ? "text/event-stream" : "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }
}
