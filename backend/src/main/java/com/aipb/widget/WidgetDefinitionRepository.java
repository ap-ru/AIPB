package com.aipb.widget;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface WidgetDefinitionRepository extends MongoRepository<WidgetDefinition, String> {
}
