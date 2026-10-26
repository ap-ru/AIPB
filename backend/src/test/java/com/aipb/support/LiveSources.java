package com.aipb.support;

import com.aipb.source.DirectSource;
import com.aipb.source.McpClient;
import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The running stack's two source connections, for live tests: the driver (what pages read through) and the MCP server.
 * The driver logs in as bank_reader: {@code -Daipb.source-uri=...}, or {@code BANK_READER_PASSWORD} from the environment.
 */
public final class LiveSources {

    private LiveSources() {}

    public static DirectSource direct(ObjectMapper mapper) {
        String uri = System.getProperty("aipb.source-uri");
        if (uri == null) {
            String pw = System.getenv("BANK_READER_PASSWORD");
            if (pw == null) throw new IllegalStateException("set -Daipb.source-uri or BANK_READER_PASSWORD to run live tests");
            uri = "mongodb://bank_reader:" + pw + "@localhost:27017/bank?authSource=bank";
        }
        return new DirectSource(mapper, uri, "bank", 1000, 30_000);
    }

    public static SourceData mcp(ObjectMapper mapper) {
        return new SourceData(new McpClient(mapper, System.getProperty("aipb.mcp-url", "http://localhost:3001/mcp"), 30), "bank");
    }
}
