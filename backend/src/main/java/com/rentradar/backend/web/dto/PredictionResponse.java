package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.domain.type.PriceVerdict;
import com.rentradar.backend.domain.type.RiskLevel;
import com.rentradar.backend.ml.FraudAssessment;
import com.rentradar.backend.service.PredictionOrchestrator;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Four blocks, matching the contract in the build reference. The interval is a
 * peer of the point estimate rather than a detail hanging off it, because the UI
 * is required to give it equal weight and a nested shape makes that natural.
 */
public record PredictionResponse(
        String id,
        Estimate estimate,
        Fraud fraud,
        Meta meta
) {

    public record Estimate(
            BigDecimal price,
            BigDecimal ciLower,
            BigDecimal ciUpper,
            String currency,
            PriceVerdict verdict
    ) {}

    public record Fraud(
            double score,
            RiskLevel level,
            String principalFactor
    ) {}

    public record Meta(
            String marketId,
            String priceModelVersion,
            String fraudModelVersion,
            int latencyMs,
            String correlationId,
            Instant createdAt
    ) {}

    public static PredictionResponse from(PredictionOrchestrator.Result result) {
        PricePrediction p = result.prediction();
        FraudAssessment f = result.fraud();

        return new PredictionResponse(
                p.id(),
                new Estimate(
                        p.predictedPrice().amount(),
                        p.ciLower().amount(),
                        p.ciUpper().amount(),
                        p.predictedPrice().currency(),
                        p.verdict()),
                new Fraud(
                        f.riskScore(),
                        f.riskLevel(),
                        f.principalFactor()),
                new Meta(
                        p.marketId(),
                        p.modelVersion(),
                        f.model().qualifiedVersion(),
                        p.latencyMs(),
                        p.correlationId(),
                        p.createdAt()));
    }
}