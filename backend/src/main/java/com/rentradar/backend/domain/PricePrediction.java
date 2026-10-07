package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.MonetaryAmount;
import com.rentradar.backend.domain.type.PriceVerdict;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored fair-price estimate with its 90 per cent confidence interval.
 *
 * The model version is persisted with every prediction. Without it a stored result
 * becomes uninterpretable once the models are retrained, which is the entanglement
 * problem described by Sculley et al. (2015) and the reason this field is mandatory.
 */
@Document(collection = "predictions")
public record PricePrediction(

        @Id String id,

        @Indexed String propertyId,

        @Indexed String userId,

        @Indexed String marketId,

        MonetaryAmount predictedPrice,

        MonetaryAmount ciLower,

        MonetaryAmount ciUpper,

        PriceVerdict verdict,

        String modelVersion,

        int latencyMs,

        String correlationId,

        @Indexed Instant createdAt
) {

    public PricePrediction {
        Objects.requireNonNull(propertyId, "propertyId must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(predictedPrice, "predictedPrice must not be null");
        Objects.requireNonNull(ciLower, "ciLower must not be null");
        Objects.requireNonNull(ciUpper, "ciUpper must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(modelVersion, "modelVersion must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }

        // compareTo already rejects a currency mismatch, so this enforces both
        // the ordering invariant and single-currency consistency in one step.
        if (ciLower.compareTo(ciUpper) >= 0) {
            throw new IllegalArgumentException(
                    "ciLower must be strictly below ciUpper: " + ciLower + " / " + ciUpper);
        }
        if (predictedPrice.compareTo(ciLower) < 0 || predictedPrice.compareTo(ciUpper) > 0) {
            throw new IllegalArgumentException(
                    "predictedPrice must lie within its interval: "
                            + predictedPrice + " not in [" + ciLower + ", " + ciUpper + "]");
        }
    }

    public MonetaryAmount intervalWidth() {
        return ciUpper.subtract(ciLower);
    }
}