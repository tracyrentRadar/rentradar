package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.MonetaryAmount;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * A named locality within a rental market: the unit that price indices, forecasts
 * and locality encodings attach to. City and district are data, never an enum, so a
 * new market or a new district is an insert rather than a code change.
 */
@Document(collection = "locations")
@CompoundIndex(
        name = "market_city_district_unique",
        def = "{'marketId': 1, 'city': 1, 'district': 1}",
        unique = true
)
public record MarketLocation(

        @Id String id,

        @Indexed String marketId,

        String city,

        String district,

        double latitude,

        double longitude,

        /** Null until enough observations exist to compute it. */
        MonetaryAmount medianPrice,

        /** Selectable in the interface. */
        boolean active,

        /** True once this locality has enough observations for locality-level pricing. */
        boolean modelled,

        Instant updatedAt
) {

    public MarketLocation {
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(city, "city must not be null");
        Objects.requireNonNull(district, "district must not be null");

        if (city.isBlank()) {
            throw new IllegalArgumentException("city must not be blank");
        }
        if (district.isBlank()) {
            throw new IllegalArgumentException("district must not be blank");
        }
        if (latitude < -90.0 || latitude > 90.0) {
            throw new IllegalArgumentException("latitude out of range: " + latitude);
        }
        if (longitude < -180.0 || longitude > 180.0) {
            throw new IllegalArgumentException("longitude out of range: " + longitude);
        }
    }

    /** Stable label for locality encoding in the feature pipeline. */
    public String localityKey() {
        return city + "/" + district;
    }
}