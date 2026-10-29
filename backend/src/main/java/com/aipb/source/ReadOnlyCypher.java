package com.aipb.source;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The read-only gate every Cypher query passes before it reaches the graph, from either reader. Two checks:
 * <ol>
 *   <li>{@link #check}, on the text (comments, string literals and backticked names removed first): no write or
 *       administration clause - CREATE, MERGE, SET, DELETE, DETACH, REMOVE, DROP, FOREACH, LOAD CSV, CALL ... IN
 *       TRANSACTIONS, USE (another database), procedures outside a short read-only list, and the admin commands;</li>
 *   <li>then the reader runs {@code EXPLAIN} and requires the planner's query type to be read-only
 *       ({@link GraphSource#explain}), which also catches a write hidden from the text check.</li>
 * </ol>
 * Neo4j Community has no role-based access control, so this gate and the read transactions the readers open are what
 * keeps the app read-only, with the database itself opened read-only in the stack ({@code server.databases.read_only}).
 */
public final class ReadOnlyCypher {

    /** Procedures a widget or the assistant may call: they only read the schema. */
    static final List<String> PROCEDURES = List.of("db.labels", "db.relationshipTypes", "db.propertyKeys", "db.schema.visualization",
            "db.schema.nodeTypeProperties", "db.schema.relTypeProperties");
    private static final Pattern WRITE = Pattern.compile(
            "(?<![\\w.$])(CREATE|MERGE|SET|DELETE|DETACH|REMOVE|DROP|FOREACH|LOAD\\s+CSV|USE(?=\\s+[\\w`])|GRANT|DENY|REVOKE|ALTER|RENAME"
                    + "|(?:START|STOP)(?=\\s+(?:DATABASE|SERVER))|TERMINATE|ENABLE\\s+SERVER|DEALLOCATE|REALLOCATE)(?![\\w$])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_TRANSACTIONS = Pattern.compile("\\bIN\\s+(\\d+\\s+)?(CONCURRENT\\s+)?TRANSACTIONS\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHOW = Pattern.compile("^\\s*SHOW\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL = Pattern.compile("(?<![\\w.$])CALL\\s+([A-Za-z_][\\w.]*)\\s*\\(", Pattern.CASE_INSENSITIVE);
    static final int MAX_LENGTH = 20_000;

    private ReadOnlyCypher() {}

    /** Refuses a query that is not a plain read, with the reason. */
    public static void check(String query) {
        List<String> problems = problems(query);
        if (!problems.isEmpty()) throw new SourceException("the source is read-only: " + String.join("; ", problems));
    }

    /** Why the query is not a plain read (empty when it is, as far as the text shows). */
    public static List<String> problems(String query) {
        List<String> out = new ArrayList<>();
        if (query == null || query.isBlank()) { out.add("the query is empty"); return out; }
        if (query.length() > MAX_LENGTH) { out.add("the query is longer than " + MAX_LENGTH + " characters"); return out; }
        String code = code(query);
        Matcher w = WRITE.matcher(code);
        while (w.find()) {
            String clause = w.group(1).toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
            String msg = clause + " is refused (only reads: MATCH, OPTIONAL MATCH, WITH, UNWIND, CALL { ... }, RETURN)";
            if (!out.contains(msg)) out.add(msg);
        }
        if (IN_TRANSACTIONS.matcher(code).find()) out.add("CALL ... IN TRANSACTIONS is refused");
        if (SHOW.matcher(code).find()) out.add("SHOW commands are refused; the schema is in the data dictionary");
        Matcher c = CALL.matcher(code);
        while (c.find()) {
            String proc = c.group(1);
            if (PROCEDURES.stream().noneMatch(p -> p.equalsIgnoreCase(proc)))
                out.add("CALL " + proc + "() is refused; procedures allowed: " + String.join(", ", PROCEDURES));
        }
        return out;
    }

    /** The query without comments, string literals and backticked names, so only its code is checked. */
    public static String code(String q) {
        StringBuilder sb = new StringBuilder(q.length());
        int i = 0, n = q.length();
        while (i < n) {
            char ch = q.charAt(i);
            if (ch == '/' && i + 1 < n && q.charAt(i + 1) == '/') { while (i < n && q.charAt(i) != '\n') i++; sb.append(' '); continue; }
            if (ch == '/' && i + 1 < n && q.charAt(i + 1) == '*') {
                int end = q.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                sb.append(' ');
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                int j = i + 1;
                while (j < n && q.charAt(j) != ch) { if (q.charAt(j) == '\\' && ch != '`') j++; j++; }
                i = Math.min(n, j + 1);
                sb.append(ch == '`' ? "x" : "''");
                continue;
            }
            sb.append(ch);
            i++;
        }
        return sb.toString();
    }
}
