package com.rentradar.android.data.remote.dto;

import java.util.List;

/**
 * Two shapes, because the backend has two.
 *
 * PredictionResponse is what POST /api/v1/predictions answers with: four
 * blocks, estimate as a peer of the rest.
 *
 * StoredPrediction is what the two GET endpoints answer with: the prediction
 * document itself, flat, with money as amount plus currency. Reading a history
 * list into PredictionResponse would silently produce nulls, so they are kept
 * apart.
 *
 * Every numeric field is a boxed type on purpose. A null tells the screen the
 * server did not send that number, and the screen can leave the space empty.
 * A primitive would quietly become 0.0, and an interval drawn from 0.0 to 0.0
 * is a lie the user cannot see through.
 *
 * Gson ignores JSON it does not recognise and leaves unmatched Java fields
 * null, so extra fields on either side are harmless. That cuts both ways: a
 * field misnamed here does not fail, it silently reads null forever. The
 * forecast block was in exactly that state until the names were checked
 * against PredictionResponse.java field by field.
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
     * The forecast block, matching PredictionResponse.Forecast on the server.
     *
     * The backend does now send this. Direction, the level at the end of the
     * horizon, its interval, and the error of the producing model.
     *
     * series is deliberately kept and is deliberately always null here. The
     * POST response carries the end point only, not the 30 daily points, so
     * nothing populates it. The full series comes from
     * GET /api/v1/forecasts/{locationId}, which the forecast detail screen will
     * call. Keeping the field means the detail button's visibility check
     * continues to compile and continues to answer no, which is the honest
     * answer until that screen exists.
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
         * trained one. The serving tier already stamps every artefact and
         * refuses to pass a stub off as trained; this carries that same
         * honesty to the screen the tenant actually reads.
         */
        public boolean servedByStub() {
            String v = meta == null ? null : meta.priceModelVersion;
            return v != null && v.toLowerCase().contains("stub");
        }
    }

    /** The GET response. The prediction document, flat. */
    public static final class StoredPrediction {
        public String id;
        public String propertyId;
        public String userId;
        public String marketId;
        public PropertyDtos.Money predictedPrice;
        public PropertyDtos.Money ciLower;
        public PropertyDtos.Money ciUpper;
        public String verdict;
        public String modelVersion;
        public Integer latencyMs;
        public String correlationId;
        public String createdAt;
    }
}