package com.aipb.repo;

import com.aipb.model.Signal;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SignalRepository extends MongoRepository<Signal, String> {
    List<Signal> findByClientIdOrderByDetectedAtDesc(String clientId);
}
