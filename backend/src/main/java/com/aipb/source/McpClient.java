package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal MCP client over the Streamable HTTP transport (JSON or SSE responses). Opens a session lazily, and opens a
 * new one when the server has expired it; a stateless server (no session id) is supported too. Knows nothing about
 * Neo4j - {@link GraphMcpSource} does.
 */
@Slf4j
@Component
public class McpClient {

    static final String PROTOCOL_VERSION = "2025-06-18";

    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicLong ids = new AtomicLong();
    private final URI url;
    private final Duration timeout;

    private volatile String sessionId;
    private volatile JsonNode serverInfo;

    public McpClient(ObjectMapper mapper, @Value("${aipb.graph.mcp-url}") String url,
                     @Value("${aipb.graph.mcp-timeout-seconds:20}") int timeoutSeconds) {
        this.mapper = mapper;
        this.url = URI.create(url);
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    public URI url() { return url; }

    /** The server's name and version, once a session is open. */
    public JsonNode serverInfo() {
        session();
        return serverInfo;
    }

    /** Lists the server's tools (name, description, inputSchema, outputSchema). */
    public JsonNode listTools() {
        return request("tools/list", mapper.createObjectNode()).path("tools");
    }

    /**
     * Calls a tool and returns its result: {@code structuredContent} and {@code content}. A tool-level failure
     * ({@code isError}) is thrown as {@link SourceException} with the server's message, so callers (and later the
     * assistant) see why a query was rejected.
     */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments));
        JsonNode result = request("tools/call", params);
        if (result.path("isError").asBoolean(false)) throw new SourceException(name + " failed: " + text(result));
        return result;
    }

    /** All text content of a tool result, joined. */
    public static String text(JsonNode result) {
        StringBuilder sb = new StringBuilder();
        result.path("content").forEach(c -> { if (c.has("text")) sb.append(sb.isEmpty() ? "" : "\n").append(c.get("text").asText()); });
        return sb.toString();
    }

    // ------------------------------------------------------------------ protocol
    private JsonNode request(String method, ObjectNode params) {
        String sid = session();
        try {
            return send(sid, method, params);
        } catch (SessionExpired e) {
            log.info("MCP session expired - opening a new one");
            synchronized (this) { if (sid.equals(sessionId)) sessionId = null; }
            return send(session(), method, params);
        }
    }

    private synchronized String session() {
        if (sessionId != null) return sessionId;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        params.set("clientInfo", mapper.createObjectNode().put("name", "aipb-backend").put("version", "1.0.0"));
        HttpResponse<String> res = post(null, rpc(ids.incrementAndGet(), "initialize", params));
        JsonNode result = unwrap(res);
        // a stateless server opens no session: requests then carry no session id
        String sid = res.headers().firstValue("mcp-session-id").orElse("");
        post(sid, mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        serverInfo = result.path("serverInfo");
        sessionId = sid;
        log.info("MCP session opened with {} {} at {}", serverInfo.path("name").asText(), serverInfo.path("version").asText(), url);
        return sid;
    }

    private JsonNode send(String sid, String method, ObjectNode params) {
        HttpResponse<String> res = post(sid, rpc(ids.incrementAndGet(), method, params));
        // The transport answers 404 for an unknown or expired session.
        if (res.statusCode() == 404) throw new SessionExpired();
        return unwrap(res);
    }

    private ObjectNode rpc(long id, String method, ObjectNode params) {
        ObjectNode m = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        m.set("params", params);
        return m;
    }

    private HttpResponse<String> post(String sid, JsonNode body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (sid != null) b.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        if (sid != null && !sid.isEmpty()) b.header("Mcp-Session-Id", sid);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SourceException("MCP server unreachable at " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("interrupted while calling the MCP server", e);
        }
    }

    /** Reads the JSON-RPC response (plain JSON, or the last data event if the server streamed SSE) and returns its result. */
    private JsonNode unwrap(HttpResponse<String> res) {
        if (res.statusCode() >= 400) throw new SourceException("MCP server answered HTTP " + res.statusCode() + ": " + res.body());
        String body = res.body();
        if (res.headers().firstValue("content-type").orElse("").startsWith("text/event-stream")) {
            String last = null;
            for (String line : body.split("\n")) if (line.startsWith("data:")) last = line.substring(5).trim();
            body = last;
        }
        if (body == null || body.isBlank()) throw new SourceException("empty response from the MCP server");
        JsonNode msg;
        try {
            msg = mapper.readTree(body);
        } catch (IOException e) {
            throw new SourceException("unreadable response from the MCP server: " + body, e);
        }
        if (msg.has("error")) throw new SourceException("MCP error: " + msg.get("error").path("message").asText(msg.get("error").toString()));
        return msg.path("result");
    }

    private static final class SessionExpired extends RuntimeException {
        SessionExpired() { super(null, null, false, false); }
    }
}
