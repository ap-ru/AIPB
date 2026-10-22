package com.aipb.semantic;

import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The semantic layer over HTTP, read-only: the catalog of what can be asked (metrics, dimensions, windows, record
 * fields) and running a semantic query. The authoring surfaces (workbench, assistant) build on these.
 */
@RestController
@RequestMapping("/api/semantic")
@RequiredArgsConstructor
public class SemanticController {

    private final SemanticModel sm;
    private final QueryExecutor executor;
    private final SourceData source;

    /** A query, and the selected client (null for the whole book). */
    public record QueryRequest(SemanticQuery query, String entityId) {}

    @GetMapping
    public Map<String, Object> catalog() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fingerprint", sm.fingerprint());
        m.put("dimensions", sm.dimensions().values());
        List<Map<String, Object>> models = new ArrayList<>();
        for (Model md : sm.models().values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", md.id());
            e.put("label", md.label());
            e.put("description", md.description());
            e.put("collection", md.collection());
            e.put("time", md.time());
            e.put("dates", md.dates().keySet());
            e.put("dimensions", md.dimensions().keySet());
            Map<String, String> segments = new LinkedHashMap<>();
            md.segments().forEach((k, s) -> segments.put(k, s.description()));
            e.put("segments", segments);
            e.put("measures", md.measures());
            if (md.records() != null) e.put("records", md.records().fields());
            models.add(e);
        }
        m.put("models", models);
        m.put("metrics", sm.derived().values());
        m.put("windows", Map.of("types", new TreeSet<>(Window.TYPES), "units", new TreeSet<>(Window.UNITS), "compare", new TreeSet<>(Window.COMPARES)));
        try {
            m.put("catalogProblems", SemanticModelValidator.againstCatalog(sm, source.catalog()));
        } catch (RuntimeException e) {
            m.put("catalogProblems", List.of("could not read the data dictionary: " + e.getMessage()));
        }
        return m;
    }

    @PostMapping("/query")
    public ResultSet query(@RequestBody QueryRequest req) {
        if (req == null || req.query() == null) throw new IllegalArgumentException("body must be {\"query\": {...}, \"entityId\": ...}");
        return executor.execute(req.query(), req.entityId());
    }

    @ExceptionHandler(SemanticQueryException.class)
    public ResponseEntity<Map<String, Object>> invalid(SemanticQueryException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", String.valueOf(e.getMessage()), "issues", e.issues()));
    }
}
