package com.rentradar.android.data.remote.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The property contract, both directions.
 *
 * <p>The request mirrors the backend's CreatePropertyRequest. The response
 * mirrors the backend's PropertyResponse, which is a flatter shape than the
 * stored document: area arrives as a magnitude plus a separate unit, and money
 * as an amount plus a separate currency, rather than as nested objects.
 *
 * <p>This file previously declared nested Area and Money objects for those two
 * fields. The server has never sent them that way, so Gson met a number where
 * it wanted an object and threw "Expected BEGIN_OBJECT but was NUMBER at path
 * $.size" on every submission. Nothing caught it because Gson's leniency only
 * covers fields that are absent, not fields of the wrong type.
 *
 * <p>Boxed types throughout, so a field the server omits reads as null rather
 * than as a confident zero.
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

    /**
     * Matches PropertyResponse on the server, field for field.
     *
     * <p>locationId is the one the forecast detail screen will need, because
     * GET /api/v1/forecasts/{locationId} is keyed on it and the prediction
     * response does not carry it.
     *
     * <p>The server also returns pricePerSquareMetre, which it computes. It is
     * kept here rather than dropped so the field is visible to anyone comparing
     * the two files, even though no screen uses it yet.
     */
    public static final class PropertyResponse {
        public String id;
        public String marketId;
        public String locationId;
        public String title;
        public Integer bedrooms;
        public Integer bathrooms;
        public BigDecimal size;
        public String sizeUnit;
        public BigDecimal price;
        public String currency;
        public BigDecimal pricePerSquareMetre;
        public Boolean furnished;
        public String source;
        public String postedAt;
    }
}