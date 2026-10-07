package com.rentradar.backend.event;

import com.rentradar.backend.ml.FraudAssessment;

import java.time.Instant;

public record HighRiskDetected(
        String propertyId,
        String marketId,
        FraudAssessment assessment,
        String correlationId,
        Instant occurredAt
) {}