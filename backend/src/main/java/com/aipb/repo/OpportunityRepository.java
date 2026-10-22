package com.aipb.repo;

import com.aipb.model.Opportunity;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface OpportunityRepository extends MongoRepository<Opportunity, String> {
    List<Opportunity> findByClientId(String clientId);
}
