package com.aipb.web;

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

/** The source-data connection (MongoDB through the MCP server). */
@RestController
@RequestMapping("/api/source")
@RequiredArgsConstructor
public class SourceController {

    private final McpClient mcp;
    private final SourceData source;

    /** Whether the MCP server is reachable, and what it exposes. Never fails: an outage is reported, not thrown. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mcpUrl", mcp.url().toString());
        m.put("database", source.database());
        try {
            JsonNode server = mcp.serverInfo();
            List<String> tools = new ArrayList<>();
            mcp.listTools().forEach(t -> tools.add(t.path("name").asText()));
            JsonNode meta = source.meta();
            m.put("connected", true);
            m.put("server", server.path("name").asText() + " " + server.path("version").asText());
            m.put("tools", tools);
            m.put("collections", source.collections());
            m.put("catalogEntries", source.catalog().size());
            if (meta != null) m.put("asOfDate", meta.path("asOfDate").asText());
        } catch (RuntimeException e) {
            m.put("connected", false);
            m.put("error", String.valueOf(e.getMessage()));
        }
        return m;
    }
}
