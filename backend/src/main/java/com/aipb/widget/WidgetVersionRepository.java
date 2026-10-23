package com.aipb.widget;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface WidgetVersionRepository extends MongoRepository<WidgetVersion, String> {
    List<WidgetVersion> findByWidgetIdOrderByVersionDesc(String widgetId);
    boolean existsByWidgetId(String widgetId);
}
