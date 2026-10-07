package com.rentradar.backend.repository;

import com.rentradar.backend.domain.RentalMarket;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RentalMarketRepository extends MongoRepository<RentalMarket, String> {

    Optional<RentalMarket> findByCode(String code);

    List<RentalMarket> findByActiveTrue();

    boolean existsByCode(String code);
}