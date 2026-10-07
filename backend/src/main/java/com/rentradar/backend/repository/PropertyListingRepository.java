package com.rentradar.backend.repository;

import com.rentradar.backend.domain.PropertyListing;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.math.BigDecimal;

public interface PropertyListingRepository extends MongoRepository<PropertyListing, String> {

    Page<PropertyListing> findByMarketIdAndActiveTrue(String marketId, Pageable pageable);

    Page<PropertyListing> findByLocationIdAndActiveTrue(String locationId, Pageable pageable);

    /**
     * Search within a market by locality, bedroom count and asking price range.
     * The price filter reaches into the embedded MonetaryAmount, which is why this
     * is a @Query rather than a derived method: 'listedPrice.amount' is a nested path.
     */
    @Query("{ 'marketId': ?0, "
            + "'locationId': ?1, "
            + "'bedrooms': { $gte: ?2, $lte: ?3 }, "
            + "'listedPrice.amount': { $gte: ?4, $lte: ?5 }, "
            + "'active': true }")
    Page<PropertyListing> search(String marketId,
                                 String locationId,
                                 int minBedrooms,
                                 int maxBedrooms,
                                 BigDecimal minPrice,
                                 BigDecimal maxPrice,
                                 Pageable pageable);

    long countByMarketIdAndActiveTrue(String marketId);
}