package com.aipb.widget;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;

public interface WidgetSubmissionRepository extends MongoRepository<WidgetSubmission, String> {
    List<WidgetSubmission> findByStatusIn(Collection<String> statuses);
    List<WidgetSubmission> findByWidgetIdAndStatusIn(String widgetId, Collection<String> statuses);
}
