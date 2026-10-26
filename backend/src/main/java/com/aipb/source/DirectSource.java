package com.aipb.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The source data read with the MongoDB driver: what every page shows (widget data, page parameter choices, filter
 * values) comes through here, not through the MCP server. It is the {@link Primary} {@link SourceReader}, so anything
 * that asks for a reader gets this one; the assistant asks for {@link SourceData} by name.
 *
 * <p>It holds its own client, separate from the app's {@code aipb} connection, logged in as {@code bank_reader}: MongoDB
 * refuses anything but reads of the source database. On top of that every call is pinned to {@code aipb.source.database},
 * write stages are refused, and the same caps as the MCP server apply (documents per query, time per query); a result cut
 * short by the document cap is marked {@code truncated}, with its full count.
 */
@Slf4j
@Primary
@Service
public class DirectSource implements SourceReader {

    /** The limit name the MCP server reports, so a capped result reads the same from either source. */
    static final String MAX_DOCUMENTS = "maxDocumentsPerQuery";
    private static final long CACHE_MS = 60_000;

    private final ObjectMapper mapper;
    private final MongoClient client;
    private final MongoDatabase db;
    private final String database;
    private final int maxDocuments;
    private final long maxTimeMs;
    private volatile String asOfDate;
    private volatile long asOfAt;

    public DirectSource(ObjectMapper mapper, @Value("${aipb.source.uri}") String uri, @Value("${aipb.source.database}") String database,
                        @Value("${aipb.source.max-documents:1000}") int maxDocuments,
                        @Value("${aipb.source.max-time-ms:10000}") long maxTimeMs) {
        this.mapper = mapper;
        this.database = database;
        this.maxDocuments = maxDocuments;
        this.maxTimeMs = maxTimeMs;
        ConnectionString cs = new ConnectionString(uri);
        this.client = MongoClients.create(MongoClientSettings.builder().applyConnectionString(cs)
                .applyToClusterSettings(b -> b.serverSelectionTimeout(5, TimeUnit.SECONDS)).build());
        this.db = client.getDatabase(database);
        log.info("Source data read directly from {} / {}", cs.getHosts(), database);
    }

    @PreDestroy
    void close() { client.close(); }

    @Override
    public String database() { return database; }

    @Override
    public Result aggregate(String collection, List<?> pipeline) {
        return aggregate(collection, pipeline == null ? JsonNodeFactory.instance.arrayNode() : (ArrayNode) mapper.valueToTree(pipeline));
    }

    @Override
    public Result aggregate(String collection, ArrayNode pipeline) {
        MongoCollection<BsonDocument> coll = collection(collection);
        List<BsonDocument> stages = new ArrayList<>();
        for (JsonNode stage : pipeline) {
            BsonDocument s = bson(stage);
            if (s.containsKey("$out") || s.containsKey("$merge")) throw new SourceException("the source is read-only: $out and $merge are refused");
            stages.add(s);
        }
        return run(() -> {
            List<BsonDocument> capped = new ArrayList<>(stages);
            capped.add(new BsonDocument("$limit", new BsonInt32(maxDocuments + 1)));
            List<BsonDocument> docs = coll.aggregate(capped).maxTime(maxTimeMs, TimeUnit.MILLISECONDS).into(new ArrayList<>());
            if (docs.size() <= maxDocuments) return result(docs, docs.size());
            List<BsonDocument> counted = new ArrayList<>(stages);
            counted.add(new BsonDocument("$count", new BsonString("n")));
            BsonDocument n = coll.aggregate(counted).maxTime(maxTimeMs, TimeUnit.MILLISECONDS).first();
            return result(docs, n == null ? null : n.getNumber("n").intValue());
        });
    }

    @Override
    public Result find(String collection, Map<String, Object> filter, Map<String, Object> projection, Map<String, Object> sort,
                       Integer limit) {
        MongoCollection<BsonDocument> coll = collection(collection);
        BsonDocument f = filter == null ? new BsonDocument() : bson(mapper.valueToTree(filter));
        return run(() -> {
            // a limit within the cap is the caller's choice, not a cut
            boolean ownLimit = limit != null && limit > 0 && limit <= maxDocuments;
            var it = coll.find(f).maxTime(maxTimeMs, TimeUnit.MILLISECONDS).limit(ownLimit ? limit : maxDocuments + 1);
            if (projection != null) it = it.projection(bson(mapper.valueToTree(projection)));
            if (sort != null) it = it.sort(bson(mapper.valueToTree(sort)));
            List<BsonDocument> docs = it.into(new ArrayList<>());
            if (docs.size() <= maxDocuments) return result(docs, ownLimit ? null : docs.size());
            return result(docs, (int) coll.countDocuments(f, new CountOptions().maxTime(maxTimeMs, TimeUnit.MILLISECONDS)));
        });
    }

    /** Cached for a minute. */
    @Override
    public String asOfDate() {
        if (asOfDate != null && System.currentTimeMillis() - asOfAt < CACHE_MS) return asOfDate;
        JsonNode docs = find("_meta", null, null, null, 1).documents();
        if (docs.isEmpty() || !docs.get(0).hasNonNull("asOfDate")) throw new SourceException("the source database has no _meta.asOfDate");
        asOfDate = docs.get(0).get("asOfDate").asText();
        asOfAt = System.currentTimeMillis();
        return asOfDate;
    }

    /** Names of the collections in the source database, sorted. */
    public List<String> collections() {
        return run(() -> {
            List<String> names = db.listCollectionNames().into(new ArrayList<>());
            Collections.sort(names);
            return List.copyOf(names);
        });
    }

    // ------------------------------------------------------------------ helpers
    private MongoCollection<BsonDocument> collection(String name) {
        return db.getCollection(SourceReader.collection(name), BsonDocument.class);
    }

    /** Extended JSON in, so {"$date": ...} in a filter or pipeline is a real date. */
    private static BsonDocument bson(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("expected a JSON object, got " + n);
        return BsonDocument.parse(n.toString());
    }

    /** At most the cap's worth of documents, marked truncated when there were more. */
    private Result result(List<BsonDocument> docs, Integer total) {
        boolean cut = docs.size() > maxDocuments;
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < Math.min(docs.size(), maxDocuments); i++) out.add(Ejson.fromBson(docs.get(i)));
        return new Result(out, out.size(), total, cut ? List.of(MAX_DOCUMENTS) : List.of());
    }

    private static <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (MongoTimeoutException e) {
            throw new SourceException("source database unreachable: " + e.getMessage(), e);
        } catch (MongoExecutionTimeoutException e) {
            throw new SourceException("the query took too long and was stopped", e);
        } catch (MongoException e) {
            throw new SourceException("source query failed: " + e.getMessage(), e);
        }
    }
}
