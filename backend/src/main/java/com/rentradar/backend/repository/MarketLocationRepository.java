package com.rentradar.backend.repository;

import com.rentradar.backend.domain.MarketLocation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MarketLocationRepository extends MongoRepository<MarketLocation, String> {

    List<MarketLocation> findByMarketIdAndActiveTrue(String marketId);

    Optional<MarketLocation> findByMarketIdAndCityAndDistrict(
            String marketId, String city, String district);

    List<MarketLocation> findByMarketIdAndCityIgnoreCaseAndActiveTrue(
            String marketId, String city);

    List<MarketLocation> findByActiveTrue();

    Optional<MarketLocation> findByMarketIdAndCityIgnoreCaseAndDistrictIgnoreCase(
            String marketId, String city, String district);

}