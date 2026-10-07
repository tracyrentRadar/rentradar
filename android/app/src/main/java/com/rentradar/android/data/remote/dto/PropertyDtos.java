package com.rentradar.android.data.remote.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors the backend's CreatePropertyRequest exactly. Same field names, same
 * order, so a reader can hold the two side by side.
 *
 * Money and area are BigDecimal here because they are BigDecimal there. Sending
 * a double would let the JSON carry 4500.000000000001 and the server's
 * @DecimalMin would still pass it, which is the sort of drift nobody notices
 * until the estimate is a few pesewas off.
 */
public final class PropertyDtos {

    private PropertyDtos() {
    }

    /** sizeUnit on the wire. Must match the backend AreaUnit enum names. */
    public static final String UNIT_SQM = "SQM";
    public static final String UNIT_SQFT = "SQFT";

    public static final class CreatePropertyRequest {
        public final String marketId;
        public final String city;
        public final String district;
        public final String title;
        public final int bedrooms;
        public final int bathrooms;
        public final Integer toilets;
        public final Integer parkingSpaces;
        public final String propertyType;
        public final BigDecimal size;
        public final String sizeUnit;
        public final BigDecimal price;
        public final String currency;
        public final Boolean furnished;
        public final List<String> amenities;
        public final String source;
        public final String postedAt;

        public CreatePropertyRequest(String marketId, String city, String district,
                                     String title, int bedrooms, int bathrooms,
                                     Integer toilets, Integer parkingSpaces,
                                     String propertyType, BigDecimal size, String sizeUnit,
                                     BigDecimal price, String currency, Boolean furnished,
                                     List<String> amenities, String source, String postedAt) {
            this.marketId = marketId;
            this.city = city;
            this.district = district;
            this.title = title;
            this.bedrooms = bedrooms;
            this.bathrooms = bathrooms;
            this.toilets = toilets;
            this.parkingSpaces = parkingSpaces;
            this.propertyType = propertyType;
            this.size = size;
            this.sizeUnit = sizeUnit;
            this.price = price;
            this.currency = currency;
            this.furnished = furnished;
            this.amenities = amenities;
            this.source = source;
            this.postedAt = postedAt;
        }
    }

    public static final class Money {
        public BigDecimal amount;
        public String currency;
    }

    public static final class Area {
        public BigDecimal magnitude;
        public String unit;
    }

    public static final class PropertyResponse {
        public String id;
        public String ownerId;
        public String marketId;
        public String locationId;
        public String title;
        public Integer bedrooms;
        public Integer bathrooms;
        public Integer toilets;
        public Integer parkingSpaces;
        public String propertyType;
        public Area size;
        public Money listedPrice;
        public Boolean furnished;
        public List<String> amenities;
        public String source;
        public String postedAt;
        public String createdAt;
    }
}