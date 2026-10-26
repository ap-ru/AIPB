package com.aipb.source;

import com.aipb.support.LiveSources;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The driver reads exactly what the MCP server reads: same documents, same JSON, same caps. Needs the running stack:
 * {@code mvn test -Dgroups=live -Dsurefire.excludedGroups=}
 */
@Tag("live")
class DirectSourceLiveTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final DirectSource direct = LiveSources.direct(mapper);
    private final SourceData mcp = LiveSources.mcp(mapper);

    @Test
    void findMatchesTheMcpServer() {
        Map<String, Object> filter = Map.of("clientId", "CL-0001");
        Map<String, Object> sort = Map.of("_id", 1);
        assertThat(direct.find("holdings", filter, null, sort, 50).documents())
                .isEqualTo(mcp.find("holdings", filter, null, sort, 50).documents());
    }

    @Test
    void aggregateMatchesTheMcpServer() throws Exception {
        ArrayNode pipeline = (ArrayNode) mapper.readTree("[{\"$match\": {\"clientId\": \"CL-0002\"}},"
                + " {\"$group\": {\"_id\": null, \"drawn\": {\"$sum\": \"$drawnUsd\"}, \"first\": {\"$min\": \"$openedDate\"}}}]");
        assertThat(direct.aggregate("holdings", pipeline).documents()).isEqualTo(mcp.aggregate("holdings", pipeline).documents());
        assertThat(direct.asOfDate()).isEqualTo(mcp.asOfDate());
    }

    @Test
    void aResultOverTheCapIsTruncatedWithItsFullCount() {
        SourceReader.Result r = direct.aggregate("revenue_monthly", List.of(Map.of("$project", Map.of("_id", 1))));
        assertThat(r.truncated()).isTrue();
        assertThat(r.count()).isEqualTo(1000);
        assertThat(r.total()).isGreaterThan(1000);
        SourceReader.Result found = direct.find("revenue_monthly", null, null, null, null);
        assertThat(found.truncated()).isTrue();
        assertThat(found.total()).isEqualTo(r.total());
    }

    @Test
    void writesAndOtherDatabasesAreRefused() {
        assertThatThrownBy(() -> direct.aggregate("clients", List.of(Map.of("$out", "x")))).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> direct.find("system.users", null, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
