package com.aipb.assistant;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface AssistantSessionRepository extends MongoRepository<AssistantSession, String> {
}
