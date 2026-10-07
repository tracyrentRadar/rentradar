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
 * null, so extra fields on either side are harmless.
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
    }

    /**
     * The backend does not send this yet, so it is always null and the forecast
     * card stays hidden. Left in place so that adding the block server-side
     * makes the card appear with no change here.
     */
    public static final class Forecast {
        public Integer horizonDays;
        public List<SeriesPoint> series;
        public String direction;
        public Double mae;
        public String modelVersion;
        public String generatedAt;
    }

    public static final class Meta {
        public String marketId;
        public String priceModelVersion;
        public String fraudModelVersion;
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