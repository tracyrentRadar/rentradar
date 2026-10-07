package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.domain.type.AreaUnit;

import java.math.BigDecimal;
import java.time.Instant;

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
        boolean furnished,
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