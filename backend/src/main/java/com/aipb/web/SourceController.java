package com.aipb.web;

import com.aipb.source.DirectSource;
import com.aipb.source.McpClient;
import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The source-data connections: MongoDB read directly (what pages show), and the MCP server (what the assistant uses). */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class SourceController {

    private final DirectSource direct;
    private final McpClient mcp;
    private final SourceData source;

    /**
     * Whether each connection works, and what it sees. {@code connected} is the direct one, since pages depend on it.
     * Never fails: an outage is reported, not thrown.
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("database", direct.database());
        try {
            JsonNode meta = direct.find("_meta", null, null, null, 1).documents();
            m.put("connected", true);
            m.put("collections", direct.collections());
            m.put("catalogEntries", direct.find("_catalog", null, Map.of("_id", 1), null, null).count());
            if (!meta.isEmpty()) m.put("asOfDate", meta.get(0).path("asOfDate").asText());
        } catch (RuntimeException e) {
            m.put("connected", false);
            m.put("error", String.valueOf(e.getMessage()));
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("url", mcp.url().toString());
        try {
            JsonNode server = mcp.serverInfo();
            List<String> tools = new ArrayList<>();
            mcp.listTools().forEach(t -> tools.add(t.path("name").asText()));
            s.put("connected", true);
            s.put("server", server.path("name").asText() + " " + server.path("version").asText());
            s.put("tools", tools);
            s.put("database", source.database());
        } catch (RuntimeException e) {
            s.put("connected", false);
            s.put("error", String.valueOf(e.getMessage()));
        }
        m.put("mcp", s);
        return m;
    }
}
