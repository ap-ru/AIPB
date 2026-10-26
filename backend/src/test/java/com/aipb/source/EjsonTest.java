package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Driver documents and MCP Extended JSON flatten to the same plain JSON. */
class EjsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void bsonFlattensLikeExtendedJson() throws Exception {
        String doc = "{\"_id\": {\"$oid\": \"65f1c0ffee0000000000abcd\"}, \"openedDate\": {\"$date\": \"2021-06-01T00:00:00.000Z\"},"
                + " \"drawnUsd\": {\"$numberLong\": \"178400000\"}, \"n\": {\"$numberInt\": \"7\"}, \"rate\": {\"$numberDouble\": \"0.25\"},"
                + " \"fee\": {\"$numberDecimal\": \"12.50\"}, \"bad\": {\"$numberDouble\": \"NaN\"}, \"tags\": [\"a\", null],"
                + " \"nested\": {\"ok\": true}}";
        JsonNode fromDriver = Ejson.fromBson(BsonDocument.parse(doc));
        JsonNode fromMcp = Ejson.simplify(mapper.readTree(doc));

        assertThat(fromDriver).isEqualTo(fromMcp);
        assertThat(fromDriver.path("openedDate").asText()).isEqualTo("2021-06-01T00:00:00.000Z");
        assertThat(fromDriver.path("drawnUsd").asLong()).isEqualTo(178_400_000L);
        assertThat(fromDriver.path("_id").asText()).isEqualTo("65f1c0ffee0000000000abcd");
        assertThat(fromDriver.path("bad").asText()).isEqualTo("NaN");
    }
}
