package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.MarketTrendForecast;
import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.domain.type.MonetaryAmount;
import com.rentradar.backend.domain.type.PriceVerdict;
import com.rentradar.backend.domain.type.RiskLevel;
import com.rentradar.backend.event.HighRiskDetected;
import com.rentradar.backend.event.PredictionCompleted;
import com.rentradar.backend.ml.FeatureVector;
import com.rentradar.backend.ml.FraudAssessment;
import com.rentradar.backend.ml.MlClient;
import com.rentradar.backend.ml.ModelRegistry;
import com.rentradar.backend.ml.ModelType;
import com.rentradar.backend.ml.PriceEstimate;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.PricePredictionRepository;
import com.rentradar.backend.repository.PropertyListingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * The algorithm in section 6 of the build reference.
 *
 * What this class deliberately does not do: write fraud alerts, write audit
 * entries, update metrics. It publishes facts and listeners handle those, which
 * keeps three writes off the request path and keeps this method readable.
 *
 * The forecast is read from storage rather than computed. Recursive multi-step
 * forecasting is the most expensive thing the system does and its answer changes
 * daily, not per request, so a fourth model call here would add latency for no
 * freshness.
 */
@Service
public class PredictionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PredictionOrchestrator.class);
    private static final String CORRELATION_ID = "correlationId";

    private final PropertyListingRepository properties;
    private final MarketLocationRepository locations;
    private final PricePredictionRepository predictions;
    private final FeatureAssembler features;
    private final ModelRegistry registry;
    private final MlClient ml;
    private final ApplicationEventPublisher events;
    private final Executor inferenceExecutor;
    private final ForecastService forecasts;

    public PredictionOrchestrator(
            PropertyListingRepository properties,
            MarketLocationRepository locations,
            PricePredictionRepository predictions,
            FeatureAssembler features,
            ModelRegistry registry,
            MlClient ml,
            ApplicationEventPublisher events,
            @org.springframework.beans.factory.annotation.Qualifier("inferenceExecutor")
            Executor inferenceExecutor,
            ForecastService forecasts) {

        this.properties = properties;
        this.locations = locations;
        this.predictions = predictions;
        this.features = features;
        this.registry = registry;
        this.ml = ml;
        this.events = events;
        this.inferenceExecutor = inferenceExecutor;
        this.forecasts = forecasts;
    }

    public Result predict(String propertyId, String userId) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put(CORRELATION_ID, correlationId);

        try {
            PropertyListing listing = properties.findById(propertyId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No property with id " + propertyId));

            MarketLocation location = locations.findById(listing.locationId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Listing " + propertyId + " references missing location "
                                    + listing.locationId()));

            // Binding rule 4. Resolved per market, never assumed.
            ModelArtefact priceModel = registry.champion(ModelType.PRICE, listing.marketId());
            ModelArtefact fraudModel = registry.champion(ModelType.FRAUD, listing.marketId());

            FeatureVector vector = features.assemble(listing, location);

            // Concurrent, so the cost is max of the two rather than the sum.
            CompletableFuture<PriceEstimate> priceCall = CompletableFuture
                    .supplyAsync(() -> ml.estimatePrice(vector, priceModel), inferenceExecutor);

            CompletableFuture<FraudAssessment> fraudCall = CompletableFuture
                    .supplyAsync(() -> ml.assessFraud(vector, fraudModel), inferenceExecutor);

            PriceEstimate estimate;
            FraudAssessment fraud;
            try {
                CompletableFuture.allOf(priceCall, fraudCall).join();
                estimate = priceCall.join();
                fraud = fraudCall.join();
            } catch (CompletionException e) {
                // Never a partial result and never a fabricated one.
                throw new ServiceDegradedException(
                        "Inference unavailable for market " + listing.marketId(), e.getCause());
            }

            PricePrediction stored = predictions.save(new PricePrediction(
                    null,
                    propertyId,
                    userId,
                    listing.marketId(),
                    new MonetaryAmount(estimate.predicted(), estimate.currency()),
                    new MonetaryAmount(estimate.lower(), estimate.currency()),
                    new MonetaryAmount(estimate.upper(), estimate.currency()),
                    verdict(listing.listedPrice(), estimate),
                    priceModel.qualifiedVersion(),
                    (int) Math.max(estimate.latencyMs(), fraud.latencyMs()),
                    correlationId,
                    Instant.now()));

            Instant now = Instant.now();
            events.publishEvent(new PredictionCompleted(stored, fraud, correlationId, now));

            if (fraud.riskLevel() == RiskLevel.HIGH) {
                events.publishEvent(new HighRiskDetected(
                        propertyId, listing.marketId(), fraud, correlationId, now));
            }

            // Read, never computed here. Absent is a valid answer: a location
            // with no stored forecast still gets an estimate and an assessment,
            // because a missing trend is not a reason to refuse the other two.
            MarketTrendForecast forecast =
                    forecasts.latestFor(listing.locationId()).orElse(null);
            if (forecast == null) {
                log.debug("No stored forecast for location {}", listing.locationId());
            }

            log.info("Prediction {} for property {} in {} ({} ms)",
                    stored.id(), propertyId, listing.marketId(), stored.latencyMs());

            return new Result(stored, fraud, forecast);

        } finally {
            MDC.remove(CORRELATION_ID);
        }
    }

    /**
     * Compared against the interval, not the point estimate. A listing inside the
     * band is at market, which is the honest answer when the model is uncertain.
     */
    private static PriceVerdict verdict(MonetaryAmount listed, PriceEstimate estimate) {
        if (listed.amount().compareTo(estimate.lower()) < 0) {
            return PriceVerdict.BELOW_MARKET;
        }
        if (listed.amount().compareTo(estimate.upper()) > 0) {
            return PriceVerdict.ABOVE_MARKET;
        }
        return PriceVerdict.AT_MARKET;
    }

    /**
     * The fraud assessment is not persisted unless HIGH, so it travels alongside.
     * The forecast is read from storage and may be null.
     */
    public record Result(PricePrediction prediction, FraudAssessment fraud,
                         MarketTrendForecast forecast) {}
}