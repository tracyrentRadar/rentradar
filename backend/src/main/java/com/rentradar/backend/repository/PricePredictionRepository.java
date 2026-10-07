package com.rentradar.backend.repository;

import com.rentradar.backend.domain.PricePrediction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PricePredictionRepository extends MongoRepository<PricePrediction, String> {

    Page<PricePrediction> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
    List<PricePrediction> findByUserIdOrderByCreatedAtDesc(String userId);

    /** Ownership-scoped read: the service never fetches by id alone. */
    Optional<PricePrediction> findByIdAndUserId(String id, String userId);

    Optional<PricePrediction> findFirstByCorrelationId(String correlationId);
}