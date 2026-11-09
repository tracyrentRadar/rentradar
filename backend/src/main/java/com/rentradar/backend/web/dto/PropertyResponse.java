package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.domain.type.AreaUnit;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The property as the API hands it back.
 *
 * <p>furnished is a Boolean, not a boolean. The domain allows "nobody said"
 * as a third state because the model treats it as its own signal, and the
 * conversational intake never asks, so it arrives as null routinely. A
 * primitive here unboxed that null and threw, which turned every chat
 * submission into a 500 after the property had already been saved.
 */
public record PropertyResponse(
        String id,
        String marketId,
        String locationId,
        String title,
        int bedrooms,
        int bathrooms,
        BigDecimal size,
        AreaUnit sizeUnit,
        BigDecimal price,
        String currency,
        BigDecimal pricePerSquareMetre,
        Boolean furnished,
        String source,
        Instant postedAt
) {
    public static PropertyResponse from(PropertyListing listing) {
        return new PropertyResponse(
                listing.id(),
                listing.marketId(),
                listing.locationId(),
                listing.title(),
                listing.bedrooms(),
                listing.bathrooms(),
                listing.size().magnitude(),
                listing.size().unit(),
                listing.listedPrice().amount(),
                listing.listedPrice().currency(),
                listing.pricePerSquareMetre(),
                listing.furnished(),
                listing.source(),
                listing.postedAt());
    }
}