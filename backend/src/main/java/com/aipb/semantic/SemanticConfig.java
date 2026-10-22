package com.aipb.semantic;

import com.aipb.source.SourceData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Loads the semantic model at startup (structural problems fail the start: the files ship with the app) and checks it
 * against the live data dictionary once the source is reachable. A mismatch there is logged, not fatal - the source
 * is a separate system and may be mid-regeneration - and is reported by GET /api/semantic.
 */
@Slf4j
@Configuration
public class SemanticConfig {

    @Bean
    public SemanticModel semanticModel(ObjectMapper mapper) {
        SemanticModel sm = SemanticModel.fromClasspath(mapper);
        log.info("Semantic model: {} dimensions, {} models, {} measures, {} derived metrics ({})", sm.dimensions().size(),
                sm.models().size(), sm.measures().size(), sm.derived().size(), sm.fingerprint().substring(0, 12));
        return sm;
    }

    @Bean
    public ApplicationRunner semanticCatalogCheck(SemanticModel sm, SourceData source) {
        return args -> {
            try {
                List<String> problems = SemanticModelValidator.againstCatalog(sm, source.catalog());
                if (problems.isEmpty()) log.info("Semantic model matches the data dictionary");
                else log.error("Semantic model disagrees with the data dictionary:\n- {}", String.join("\n- ", problems));
            } catch (RuntimeException e) {
                log.warn("Could not check the semantic model against the data dictionary: {}", e.getMessage());
            }
        };
    }
}
