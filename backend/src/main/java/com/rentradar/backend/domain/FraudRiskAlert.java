package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.RiskLevel;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * A recorded fraud risk assessment. Written only when the assessed level is HIGH.
 * The output is advisory: it records that a listing is unusual for its locality,
 * never that a person has committed fraud.
 */
@Document(collection = "fraud_alerts")
@CompoundIndex(name = "level_triggered", def = "{'riskLevel': 1, 'triggeredAt': -1}")
public record FraudRiskAlert(

        @Id String id,

        @Indexed String propertyId,

        @Indexed String marketId,

        double riskScore,

        RiskLevel riskLevel,

        double reconstructionError,

        /** The feature contributing most to the reconstruction error, for explainability. */
        String principalFactor,

        String modelVersion,

        Instant triggeredAt
) {

    public FraudRiskAlert {
        Objects.requireNonNull(propertyId, "propertyId must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(modelVersion, "modelVersion must not be null");
        Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");

        if (riskScore < 0.0 || riskScore > 1.0) {
            throw new IllegalArgumentException("riskScore must be in [0,1], was " + riskScore);
        }
        if (reconstructionError < 0.0) {
            throw new IllegalArgumentException("reconstructionError must not be negative");
        }
    }
}