package com.aipb.repo;

import com.aipb.model.Layout;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LayoutRepository extends MongoRepository<Layout, String> {
}
