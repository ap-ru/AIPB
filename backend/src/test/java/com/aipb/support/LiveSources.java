package com.aipb.support;

import com.aipb.source.GraphMcpSource;
import com.aipb.source.GraphSource;
import com.aipb.source.McpClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The running stack's two source connections, for live tests: the driver (what pages read through) and the MCP server.
 * The driver logs in to Neo4j with {@code -Daipb.graph-password=...}, or {@code NEO4J_PASSWORD} from the environment.
 */
public final class LiveSources {

    private LiveSources() {}

    public static GraphSource graph(ObjectMapper mapper) {
        String pw = System.getProperty("aipb.graph-password", System.getenv("NEO4J_PASSWORD"));
        if (pw == null) throw new IllegalStateException("set -Daipb.graph-password or NEO4J_PASSWORD to run live tests");
        return new GraphSource(mapper, System.getProperty("aipb.graph-uri", "bolt://localhost:7687"), "neo4j", pw, "neo4j", 1000, 30_000);
    }

    public static GraphMcpSource mcp(ObjectMapper mapper, GraphSource graph) {
        return new GraphMcpSource(new McpClient(mapper, System.getProperty("aipb.mcp-url", "http://localhost:3001/mcp/"), 30), graph, mapper, "neo4j", 1000);
    }
}
