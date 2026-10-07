package com.rentradar.backend.repository;

import com.rentradar.backend.domain.MarketTrendForecast;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MarketTrendForecastRepository
        extends MongoRepository<MarketTrendForecast, String> {

    /** The current forecast for a location: most recently generated. */
    Optional<MarketTrendForecast> findFirstByLocationIdOrderByGeneratedAtDesc(String locationId);

    List<MarketTrendForecast> findByMarketIdAndGeneratedAtGreaterThanEqual(
            String marketId, Instant since);

    boolean existsByLocationIdAndGeneratedAt(String locationId, Instant generatedAt);


}