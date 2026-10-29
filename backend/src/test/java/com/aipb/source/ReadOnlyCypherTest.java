package com.aipb.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The read-only gate's text check: writes and administration are refused wherever they hide; reads pass. */
class ReadOnlyCypherTest {

    @Test
    void readsPass() {
        for (String q : new String[]{
                "MATCH (c:Client {clientId: $clientId})-[:HOLDS]->(h:Holding) RETURN h.productName AS product, h.drawnUsd AS drawnUsd",
                "MATCH (r:RevenueMonth) WHERE r.month >= datetime($start) WITH r.lob AS lob, sum(r.totalUsd) AS usd RETURN lob, usd ORDER BY usd DESC LIMIT 5",
                "UNWIND $ids AS id OPTIONAL MATCH (c:Client {clientId: id}) RETURN id, c.name AS name",
                "MATCH (c:Client) CALL (c) { MATCH (c)-[:HOLDS]->(h) RETURN count(h) AS n } RETURN c.name AS name, n",
                "MATCH (n:Signal) WHERE n.title CONTAINS 'CREATE' AND n.stage <> 'Resolved' RETURN n.title AS title // SET in a comment",
                "MATCH (n:Holding) RETURN n.reset AS reset, n.settled AS settled, n {.*} AS props",
                "CALL db.labels() YIELD label RETURN label",
                "WITH datetime.truncate('year', datetime($asOfDate)) AS yearStart MATCH (r:RevenueMonth) WHERE r.month >= yearStart RETURN sum(r.totalUsd) AS ytd"}) {
            assertThat(ReadOnlyCypher.problems(q)).as(q).isEmpty();
        }
    }

    @Test
    void writesAndAdministrationAreRefused() {
        for (String q : new String[]{
                "CREATE (x:Client {name: 'x'})", "MATCH (c:Client) SET c.name = 'x'", "MATCH (c) DETACH DELETE c",
                "MERGE (c:Client {clientId: 'x'})", "MATCH (c:Client) REMOVE c.name", "LOAD CSV FROM 'file:///x' AS r RETURN r",
                "MATCH (c) CALL (c) { DELETE c } IN TRANSACTIONS", "CREATE INDEX x FOR (n:Client) ON (n.name)", "DROP CONSTRAINT x",
                "USE system SHOW USERS", "SHOW DATABASES", "CALL dbms.listConfig()", "CALL apoc.periodic.iterate('a', 'b', {})",
                "MATCH (c:Client) FOREACH (x IN [1] | SET c.n = x)", "STOP DATABASE neo4j", "ALTER USER neo4j SET PASSWORD 'x'",
                "match (c:Client) set c.x = 1", "MATCH (c:Client) /* comment */ CREATE (d:X)"}) {
            assertThat(ReadOnlyCypher.problems(q)).as(q).isNotEmpty();
        }
        assertThatThrownBy(() -> ReadOnlyCypher.check("MATCH (c) DELETE c")).isInstanceOf(SourceException.class).hasMessageContaining("DELETE is refused");
    }

    @Test
    void emptyAndOversizedQueriesAreRefused() {
        assertThat(ReadOnlyCypher.problems(" ")).isNotEmpty();
        assertThat(ReadOnlyCypher.problems("RETURN '" + "x".repeat(ReadOnlyCypher.MAX_LENGTH) + "'")).isNotEmpty();
    }
}
