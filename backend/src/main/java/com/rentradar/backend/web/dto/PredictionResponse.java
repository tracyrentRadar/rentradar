package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.MarketTrendForecast;
import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.domain.type.PriceVerdict;
import com.rentradar.backend.domain.type.RiskLevel;
import com.rentradar.backend.domain.type.TrendDirection;
import com.rentradar.backend.ml.FraudAssessment;
import com.rentradar.backend.service.PredictionOrchestrator;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Four blocks, matching the contract in the build reference. The interval is a
 * peer of the point estimate rather than a detail hanging off it, because the UI
 * is required to give it equal weight and a nested shape makes that natural.
 *
 * <p>forecast is nullable by design. A location with no stored forecast still
 * gets an estimate and a fraud assessment; a missing trend is not a reason to
 * refuse the other two. The client has to handle a null here anyway, because the
 * offline cache can hold a prediction whose forecast has since expired.
 *
 * <p>The forecast block carries the value at the end of the horizon rather than
 * the whole daily series. Thirty points belong on the forecast detail screen,
 * served by the forecasts endpoint, not repeated inside every prediction.
 */
public record PredictionResponse(
        String id,
        Estimate estimate,
        Fraud fraud,
        Forecast forecast,
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

    public record Forecast(
            TrendDirection direction,
            BigDecimal value,
            BigDecimal lower,
            BigDecimal upper,
            String currency,
            int horizonDays,

            /** Mean absolute error of the producing model on held out data. */
            double meanAbsoluteError,

            String modelVersion,
            Instant generatedAt
    ) {}

    public record Meta(
            String marketId,
            String priceModelVersion,
            String fraudModelVersion,
            String trendModelVersion,
            int latencyMs,
            String correlationId,
            Instant createdAt
    ) {}

    public static PredictionResponse from(PredictionOrchestrator.Result result) {
        PricePrediction p = result.prediction();
        FraudAssessment f = result.fraud();
        Forecast forecast = forecastOf(result.forecast());

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
                forecast,
                new Meta(
                        p.marketId(),
                        p.modelVersion(),
                        f.model().qualifiedVersion(),
                        forecast == null ? null : forecast.modelVersion(),
                        p.latencyMs(),
                        p.correlationId(),
                        p.createdAt()));
    }

    /**
     * The last point in the series is the answer to "where will this be in
     * thirty days", which is what the product promises. Reading the first point
     * would quietly answer a different question.
     */
    private static Forecast forecastOf(MarketTrendForecast stored) {
        if (stored == null || stored.series().isEmpty()) {
            return null;
        }
        var end = stored.series().get(stored.series().size() - 1);
        return new Forecast(
                stored.direction(),
                end.value().amount(),
                end.lower().amount(),
                end.upper().amount(),
                end.value().currency(),
                stored.horizonDays(),
                stored.mae(),
                stored.modelVersion(),
                stored.generatedAt());
    }
}