package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.RentalMarket;
import com.rentradar.backend.referencedata.provider.LocationProvider;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.RentalMarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Finds the MarketLocation for a city and district, creating it on first sight
 * by geocoding through Nominatim.
 *
 * The locations collection fills itself from real listings rather than being
 * pre-seeded from a gazetteer, so every row has at least one property behind it.
 */
@Service
public class LocationResolver {

    private static final Logger log = LoggerFactory.getLogger(LocationResolver.class);

    private final MarketLocationRepository locations;
    private final RentalMarketRepository markets;
    private final LocationProvider geocoder;

    public LocationResolver(
            MarketLocationRepository locations,
            RentalMarketRepository markets,
            LocationProvider geocoder) {

        this.locations = locations;
        this.markets = markets;
        this.geocoder = geocoder;
    }

    public MarketLocation resolve(String marketCode, String city, String district) {
        RentalMarket market = requireMarket(marketCode);

        String cleanCity = clean(city);
        String cleanDistrict = clean(district);

        return locations
                .findByMarketIdAndCityIgnoreCaseAndDistrictIgnoreCase(
                        market.code(), cleanCity, cleanDistrict)
                .orElseGet(() -> create(market, cleanCity, cleanDistrict));
    }

    /**
     * Binding rule 8: a request whose market cannot be resolved fails loudly.
     * No default market, no silent fallback.
     */
    public RentalMarket requireMarket(String marketCode) {
        if (marketCode == null || marketCode.isBlank()) {
            throw new UnknownMarketException("marketId is required");
        }

        return markets.findByCode(marketCode.trim().toUpperCase())
                .filter(RentalMarket::active)
                .orElseThrow(() -> new UnknownMarketException(
                        "No active market with code " + marketCode));
    }

    private MarketLocation create(RentalMarket market, String city, String district) {
        var coordinates = geocoder
                .resolve(city, district, market.name())
                // Nominatim often has no node for a district but always has the
                // city. A city-level fix is a worse coordinate, not a wrong one.
                .or(() -> geocoder.resolve(city, city, market.name()))
                .orElseThrow(() -> new LocationNotResolvableException(
                        "Could not geocode " + district + ", " + city + ", " + market.name()));

        MarketLocation created = new MarketLocation(
                null,
                market.code(),
                city,
                district,
                coordinates.latitude(),
                coordinates.longitude(),
                null,       // medianPrice, computed once observations exist
                true,       // active
                false,      // modelled, set when the locality has enough data
                Instant.now());

        try {
            MarketLocation saved = locations.save(created);
            log.info("Created location {}/{} in {} at {}, {}",
                    city, district, market.code(),
                    coordinates.latitude(), coordinates.longitude());
            return saved;

        } catch (DuplicateKeyException e) {
            // Two listings for the same new district arriving together. The
            // compound unique index settles it; re-read the winner.
            return locations
                    .findByMarketIdAndCityIgnoreCaseAndDistrictIgnoreCase(
                            market.code(), city, district)
                    .orElseThrow(() -> e);
        }
    }

    /** Collapses whitespace so "East  Legon " and "East Legon" are one locality. */
    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("city and district are required");
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    public MarketLocation resolve(RentalMarket market, String city, String district) {
        String cleanCity = clean(city);
        String cleanDistrict = clean(district);
        return locations
                .findByMarketIdAndCityIgnoreCaseAndDistrictIgnoreCase(
                        market.code(), cleanCity,cleanDistrict
                ).orElseGet(()-> create(market,city,cleanDistrict));
    }
}