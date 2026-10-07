package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.type.RiskLevel;
import com.rentradar.backend.domain.type.TrendDirection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic stand-in for the inference service. Same input, same output,
 * every run, so the orchestrator can be tested against real values rather than
 * mocks. The numbers are plausible, not trained, and never reach the evaluation
 * chapter.
 *
 * Replaced by setting rentradar.ml.mode=http once FastAPI exists.
 */
@Component
@ConditionalOnProperty(name = "rentradar.ml.mode", havingValue = "stub", matchIfMissing = true)
public class StubMlClient implements MlClient {

    private static final BigDecimal INTERVAL_WIDTH = new BigDecimal("0.12");
    private static final BigDecimal FURNISHED_UPLIFT = new BigDecimal("1.12");

    @Override
    public PriceEstimate estimatePrice(FeatureVector features, ModelArtefact model) {
        long started = System.nanoTime();

        BigDecimal estimate = baseRatePerSquareMetre(features.localityKey())
                .multiply(features.sizeSquareMetres())
                .multiply(bedroomFactor(features.bedrooms()));

        if (features.furnished()) {
            estimate = estimate.multiply(FURNISHED_UPLIFT);
        }

        estimate = estimate.setScale(2, RoundingMode.HALF_UP);
        BigDecimal margin = estimate.multiply(INTERVAL_WIDTH).setScale(2, RoundingMode.HALF_UP);

        return new PriceEstimate(
                estimate,
                estimate.subtract(margin),
                estimate.add(margin),
                features.currency(),
                model,
                elapsedMs(started));
    }

    @Override
    public FraudAssessment assessFraud(FeatureVector features, ModelArtefact model) {
        long started = System.nanoTime();

        BigDecimal expected = estimatePrice(features, model).predicted();
        double ratio = features.listedPrice().doubleValue() / expected.doubleValue();

        // Log distance, so half price and double price are equally anomalous.
        double deviation = Math.abs(Math.log(ratio));
        double score = Math.min(1.0, deviation * 1.4);

        RiskLevel level = score < 0.30 ? RiskLevel.LOW
                : score < 0.60 ? RiskLevel.MEDIUM
                : RiskLevel.HIGH;

        String factor = score < 0.30
                ? "Asking price is consistent with this locality"
                : ratio < 1
                ? "Asking price is well below the locality norm"
                : "Asking price is well above the locality norm";

        return new FraudAssessment(
                round(score), level, round(deviation), factor, model, elapsedMs(started));
    }

    @Override
    public TrendForecast forecastTrend(
            String marketId, String locationId, int horizonDays, ModelArtefact model) {

        long started = System.nanoTime();

        BigDecimal base = baseRatePerSquareMetre(locationId);
        double drift = ((locationId.hashCode() % 7) - 3) / 1000.0;

        List<TrendForecast.Point> series = new ArrayList<>(horizonDays);
        for (int day = 1; day <= horizonDays; day++) {
            BigDecimal value = base
                    .multiply(BigDecimal.valueOf(Math.pow(1 + drift, day)))
                    .setScale(2, RoundingMode.HALF_UP);

            BigDecimal margin = value.multiply(INTERVAL_WIDTH).setScale(2, RoundingMode.HALF_UP);
            series.add(new TrendForecast.Point(
                    day, value, value.subtract(margin), value.add(margin)));
        }

        TrendDirection direction = drift > 0.0005 ? TrendDirection.RISING
                : drift < -0.0005 ? TrendDirection.FALLING
                : TrendDirection.STABLE;

        return new TrendForecast(series, direction, 0.0, model, elapsedMs(started));
    }

    private static BigDecimal baseRatePerSquareMetre(String localityKey) {
        int spread = Math.floorMod(localityKey.hashCode(), 36);
        return BigDecimal.valueOf(25L + spread);
    }

    private static BigDecimal bedroomFactor(int bedrooms) {
        return BigDecimal.ONE.add(BigDecimal.valueOf((bedrooms - 1) * 0.04));
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}