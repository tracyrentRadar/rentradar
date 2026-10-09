package com.rentradar.android.data.remote.dto;

import java.util.List;

/**
 * Three shapes.
 *
 * PredictionResponse is what POST /api/v1/predictions answers with: four
 * blocks, estimate as a peer of the rest.
 *
 * Summary is what the two GET endpoints answer with, one row of history as the
 * Home and History screens draw it. It replaces the old StoredPrediction, which
 * mirrored the raw database document and could not describe its own row: it had
 * a fair price and a verdict but no bedrooms, no locality and no asking price,
 * because those live on the property.
 *
 * Every numeric field is boxed on purpose. A null says the server did not send
 * that number and the screen can leave the space empty. A primitive would
 * quietly become 0 and put "0 bed" on screen, which is a claim nothing supports.
 */
public final class PredictionDtos {

    private PredictionDtos() {
    }

    public static final String VERDICT_BELOW = "BELOW_MARKET";
    public static final String VERDICT_AT = "AT_MARKET";
    public static final String VERDICT_ABOVE = "ABOVE_MARKET";

    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";

    public static final String TREND_RISING = "RISING";
    public static final String TREND_STABLE = "STABLE";
    public static final String TREND_FALLING = "FALLING";

    /** Matches CreatePredictionRequest. One field, the id of a listing already posted. */
    public static final class PredictionRequest {
        public final String propertyId;

        public PredictionRequest(String propertyId) {
            this.propertyId = propertyId;
        }
    }

    public static final class Estimate {
        public Double price;
        public Double ciLower;
        public Double ciUpper;
        public String currency;
        public String verdict;
    }

    public static final class Fraud {
        public Double score;
        public String level;
        public String principalFactor;
    }

    public static final class SeriesPoint {
        public Integer day;
        public Double value;
        public Double lower;
        public Double upper;
    }

    /**
     * Matches PredictionResponse.Forecast on the server: direction, the level at
     * the end of the horizon, its interval, and the producing model's error.
     *
     * series is kept and is always null here. The POST response carries the end
     * point only. The thirty daily points come from GET /api/v1/forecasts/{id},
     * which the forecast detail screen will call.
     */
    public static final class Forecast {
        public String direction;
        public Double value;
        public Double lower;
        public Double upper;
        public String currency;
        public Integer horizonDays;
        public Double meanAbsoluteError;
        public String modelVersion;
        public String generatedAt;
        public List<SeriesPoint> series;
    }

    public static final class Meta {
        public String marketId;
        public String priceModelVersion;
        public String fraudModelVersion;
        public String trendModelVersion;
        public Long latencyMs;
        public String correlationId;
        public String createdAt;
    }

    /** The POST response. */
    public static final class PredictionResponse {
        public String id;
        public Estimate estimate;
        public Fraud fraud;
        public String propertyId;
        public Forecast forecast;
        public Meta meta;

        /**
         * True when the figure came from a placeholder model rather than a
         * trained one. The serving tier already refuses to pass a stub off as
         * trained; this carries that honesty to the screen the tenant reads.
         */
        public boolean servedByStub() {
            String v = meta == null ? null : meta.priceModelVersion;
            return v != null && v.toLowerCase().contains("stub");
        }
    }

    /**
     * One row of history. Matches PredictionSummaryResponse on the server.
     *
     * bedrooms, city, locality and askedPrice are null when the listing behind
     * the estimate has since been deleted. The estimate is still real and still
     * belongs in the list, so the row renders with what it has.
     */
    public static final class Summary {
        public String id;
        public String propertyId;
        public Integer bedrooms;
        public String city;
        public String locality;
        public Double askedPrice;
        public Double fairPrice;
        public String currency;
        public String verdict;
        public String modelVersion;
        public String createdAt;
    }
}