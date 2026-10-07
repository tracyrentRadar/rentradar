package com.rentradar.backend.service;

import com.rentradar.backend.domain.type.AreaUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * What the service needs to create a listing, independent of the web layer.
 *
 * <p>The optional counts and furnishing are nullable rather than defaulted. A
 * caller that does not know whether a flat is furnished must be able to say so,
 * because "unstated" and "unfurnished" are different facts and the model is
 * built to tell them apart.
 */
public record NewPropertyCommand(
        String marketCode,
        String city,
        String district,
        String title,
        int bedrooms,
        int bathrooms,
        Integer toilets,
        Integer parkingSpaces,
        String propertyType,
        BigDecimal size,
        AreaUnit sizeUnit,
        BigDecimal price,
        String currency,
        Boolean furnished,
        List<String> amenities,
        String source,
        Instant postedAt
) {
    public NewPropertyCommand {
        amenities = amenities == null ? List.of() : List.copyOf(amenities);
    }
}