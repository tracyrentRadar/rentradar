package com.rentradar.backend.event;

import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.ml.FraudAssessment;

import java.time.Instant;

/** Published after the prediction is durably stored, never before. */
public record PredictionCompleted(
        PricePrediction prediction,
        FraudAssessment fraud,
        String correlationId,
        Instant occurredAt
) {}