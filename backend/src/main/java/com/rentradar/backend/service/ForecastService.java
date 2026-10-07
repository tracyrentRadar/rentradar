package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.MarketTrendForecast;
import com.rentradar.backend.domain.MarketTrendForecast.ForecastPoint;
import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.RentalMarket;
import com.rentradar.backend.domain.type.MonetaryAmount;
import com.rentradar.backend.ml.MlClient;
import com.rentradar.backend.ml.ModelRegistry;
import com.rentradar.backend.ml.ModelType;
import com.rentradar.backend.ml.TrendForecast;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.MarketTrendForecastRepository;
import com.rentradar.backend.repository.RentalMarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Regenerates stored forecasts. Nothing here runs on a user request: a forecast
 * is read from storage, never computed on demand.
 */
@Service
public class ForecastService {

    private static final Logger log = LoggerFactory.getLogger(ForecastService.class);

    private final MarketLocationRepository locations;
    private final MarketTrendForecastRepository forecasts;
    private final RentalMarketRepository markets;
    private final ModelRegistry registry;
    private final MlClient ml;
    private final int horizonDays;

    public ForecastService(
            MarketLocationRepository locations,
            MarketTrendForecastRepository forecasts,
            RentalMarketRepository markets,
            ModelRegistry registry,
            MlClient ml,
            @Value("${rentradar.forecast.horizon-days:30}") int horizonDays) {

        this.locations = locations;
        this.forecasts = forecasts;
        this.markets = markets;
        this.registry = registry;
        this.ml = ml;
        this.horizonDays = horizonDays;
    }

    /**
     * One location failing must not stop the rest. A partial refresh is a
     * degraded state worth logging, not a reason to leave every location stale.
     */
    public int regenerateAll() {
        int written = 0;

        for (MarketLocation location : locations.findByActiveTrue()) {
            try {
                regenerateFor(location);
                written++;
            } catch (RuntimeException e) {
                log.error("Forecast failed for location {} ({}): {}",
                        location.id(), location.localityKey(), e.getMessage());
            }
        }

        log.info("Forecast refresh complete: {} locations written", written);
        return written;
    }

    public MarketTrendForecast regenerateFor(MarketLocation location) {
        RentalMarket market = markets.findByCode(location.marketId())
                .orElseThrow(() -> new UnknownMarketException(
                        "Location " + location.id() + " references unknown market "
                                + location.marketId()));

        ModelArtefact model = registry.champion(ModelType.TREND, market.code());

        TrendForecast produced = ml.forecastTrend(
                market.code(), location.id(), horizonDays, model);

        // The model works in bare numbers. Currency is attached here, from the
        // market, so binding rule 2 holds at the storage boundary.
        List<ForecastPoint> series = produced.series().stream()
                .map(point -> new ForecastPoint(
                        point.day(),
                        new MonetaryAmount(point.value(), market.currency()),
                        new MonetaryAmount(point.lower(), market.currency()),
                        new MonetaryAmount(point.upper(), market.currency())))
                .toList();

        return forecasts.save(new MarketTrendForecast(
                null,
                market.code(),
                location.id(),
                horizonDays,
                series,
                produced.direction(),
                produced.meanAbsoluteError(),
                model.qualifiedVersion(),
                Instant.now()));
    }

    public Optional<MarketTrendForecast> latestFor(String locationId) {
        return forecasts.findFirstByLocationIdOrderByGeneratedAtDesc(locationId);
    }
}