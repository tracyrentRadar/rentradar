package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.Area;
import com.rentradar.backend.domain.type.MonetaryAmount;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A property offered for rent at a point in time. Stored as observed: the asking
 * price keeps its currency and the floor area keeps the unit it was quoted in.
 * Nothing is normalised at write time, because feature preprocessing belongs to the
 * model pipeline and must stay reproducible across retraining.
 *
 * <p>toilets, parkingSpaces, propertyType and amenities are nullable or empty by
 * design. Observed listings fill them inconsistently, and whether an agent
 * bothered is itself predictive. furnished is a Boolean for the same reason:
 * three quarters of observed listings state nothing, and "unstated" is a
 * different fact from "unfurnished". A primitive would silently merge them.
 */
@Document(collection = "properties")
@CompoundIndex(
        name = "market_location_bedrooms",
        def = "{'marketId': 1, 'locationId': 1, 'bedrooms': 1}"
)
public record PropertyListing(

        @Id String id,

        @Indexed String ownerId,

        @Indexed String marketId,

        @Indexed String locationId,

        String title,

        int bedrooms,

        int bathrooms,

        /** Null when the listing did not say. */
        Integer toilets,

        /** Null when the listing did not say. */
        Integer parkingSpaces,

        /** Free text as published, e.g. "Apartment", "Detached Duplex". */
        String propertyType,

        Area size,

        MonetaryAmount listedPrice,

        /** Null means unstated, which is not the same as false. */
        Boolean furnished,

        /** As published. Empty when none were listed, which is itself a signal. */
        List<String> amenities,

        /** Provenance tag, validated against the owning market's valid sources. */
        String source,

        Instant postedAt,

        boolean active,

        Instant createdAt
) {

    private static final int TITLE_MAX = 140;
    private static final int ROOM_MIN = 1;
    private static final int ROOM_MAX = 10;
    private static final int COUNT_MAX = 20;

    public PropertyListing {
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(locationId, "locationId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(size, "size must not be null");
        Objects.requireNonNull(listedPrice, "listedPrice must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(postedAt, "postedAt must not be null");

        title = title.trim();
        if (title.isEmpty() || title.length() > TITLE_MAX) {
            throw new IllegalArgumentException(
                    "title must be 1 to " + TITLE_MAX + " characters, was " + title.length());
        }
        if (bedrooms < ROOM_MIN || bedrooms > ROOM_MAX) {
            throw new IllegalArgumentException("bedrooms must be " + ROOM_MIN + " to " + ROOM_MAX);
        }
        if (bathrooms < ROOM_MIN || bathrooms > ROOM_MAX) {
            throw new IllegalArgumentException("bathrooms must be " + ROOM_MIN + " to " + ROOM_MAX);
        }
        if (toilets != null && (toilets < 0 || toilets > COUNT_MAX)) {
            throw new IllegalArgumentException("toilets must be 0 to " + COUNT_MAX);
        }
        if (parkingSpaces != null && (parkingSpaces < 0 || parkingSpaces > COUNT_MAX)) {
            throw new IllegalArgumentException("parkingSpaces must be 0 to " + COUNT_MAX);
        }
        if (!listedPrice.isPositive()) {
            throw new IllegalArgumentException("listedPrice must be positive, was " + listedPrice);
        }
        if (source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }

        amenities = amenities == null ? List.of() : List.copyOf(amenities);
    }

    /** Asking price per square metre, the derived feature most comparable across listings. */
    public java.math.BigDecimal pricePerSquareMetre() {
        return listedPrice.amount()
                .divide(size.inSquareMetres(), java.math.MathContext.DECIMAL64);
    }
}