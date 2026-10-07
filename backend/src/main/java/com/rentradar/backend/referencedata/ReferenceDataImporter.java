package com.rentradar.backend.referencedata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentradar.backend.config.ReferenceDataProperties;
import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.RentalMarket;
import com.rentradar.backend.referencedata.provider.LocationCatalogProvider;
import com.rentradar.backend.referencedata.provider.LocationCatalogProvider.DiscoveredPlace;
import com.rentradar.backend.referencedata.provider.MarketMetadataProvider;
import com.rentradar.backend.referencedata.provider.MarketMetadataProvider.MarketMetadata;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.RentalMarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers and imports the geography of each configured market.
 *
 * Runs only with --import-reference-data. Geocoding services are third parties,
 * and making them a startup dependency would tie this application's availability
 * to theirs.
 *
 * The import writes documents and a snapshot file recording what was imported
 * and when. The snapshot is committed, which is what makes the reference dataset
 * reproducible and citable rather than re-derived on every run.
 */
@Component
public class ReferenceDataImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataImporter.class);
    private static final String FLAG = "import-reference-data";
    private static final Path SNAPSHOT = Path.of("reference-data", "markets-snapshot.json");
    private static final String ATTRIBUTION =
            "Geography: © OpenStreetMap contributors (ODbL) via Overpass. "
                    + "Country metadata: REST Countries.";

    private final RentalMarketRepository markets;
    private final MarketLocationRepository locations;
    private final MarketMetadataProvider metadataProvider;
    private final LocationCatalogProvider catalogProvider;
    private final ReferenceDataProperties properties;
    private final ObjectMapper objectMapper;

    public ReferenceDataImporter(RentalMarketRepository markets,
                                 MarketLocationRepository locations,
                                 MarketMetadataProvider metadataProvider,
                                 LocationCatalogProvider catalogProvider,
                                 ReferenceDataProperties properties,
                                 ObjectMapper objectMapper) {
        this.markets = markets;
        this.locations = locations;
        this.metadataProvider = metadataProvider;
        this.catalogProvider = catalogProvider;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(FLAG)) {
            return;
        }
        log.info("Reference data import starting for markets {}", properties.markets());

        List<MarketSnapshot> snapshots = new ArrayList<>();
        for (String code : properties.markets()) {
            importMarket(code).ifPresent(snapshots::add);
        }

        writeSnapshot(snapshots);
        log.info("Reference data import complete");
    }

    private Optional<MarketSnapshot> importMarket(String countryCode) {
        Optional<MarketMetadata> metadata = metadataProvider.lookup(countryCode);
        if (metadata.isEmpty()) {
            log.error("Skipping {}: country metadata unavailable", countryCode);
            return Optional.empty();
        }
        MarketMetadata meta = metadata.get();
        log.info("Market {}: {}, currency {}, locale {}",
                countryCode, meta.countryName(), meta.currencyCode(), meta.locale());

        RentalMarket market = markets.findByCode(countryCode).orElseGet(() ->
                markets.save(new RentalMarket(
                        null,
                        countryCode,
                        meta.countryName(),
                        meta.currencyCode(),
                        properties.defaultAreaUnit(),
                        meta.locale(),
                        Set.copyOf(properties.validSources()),
                        true,
                        Instant.now())));

        List<DiscoveredPlace> cities = catalogProvider.discoverCities(countryCode);
        if (cities.isEmpty()) {
            log.warn("No cities discovered for {}", countryCode);
            return Optional.empty();
        }

        // Population is often absent in OSM; places without it sort last rather
        // than being treated as having a population of zero.
        List<DiscoveredPlace> selected = cities.stream()
                .sorted(Comparator.comparing(
                                (DiscoveredPlace p) -> p.population() == null ? -1 : p.population())
                        .reversed())
                .limit(properties.maxCitiesPerMarket())
                .toList();

        log.info("Selected {} of {} discovered cities in {}",
                selected.size(), cities.size(), countryCode);

        List<LocationSnapshot> imported = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (DiscoveredPlace city : selected) {
            // The city itself is selectable, so a user can pick it without a district.
            upsert(market, city.name(), city.name(),
                    city.latitude(), city.longitude(), seen, imported);

            List<DiscoveredPlace> localities =
                    catalogProvider.discoverLocalities(city, properties.localityRadiusMetres());

            for (DiscoveredPlace locality : localities) {
                upsert(market, city.name(), locality.name(),
                        locality.latitude(), locality.longitude(), seen, imported);
            }
        }

        log.info("Market {}: {} localities in total", countryCode, imported.size());

        return Optional.of(new MarketSnapshot(
                market.code(), market.name(), market.currency(),
                market.defaultAreaUnit().name(), market.locale(),
                List.copyOf(market.validSources()), imported));
    }

    private void upsert(RentalMarket market, String city, String district,
                        double latitude, double longitude,
                        Set<String> seen, List<LocationSnapshot> out) {

        // Overpass can return the same place as both a node and a way.
        String key = city + "|" + district;
        if (!seen.add(key)) {
            return;
        }

        boolean exists = locations
                .findByMarketIdAndCityAndDistrict(market.id(), city, district)
                .isPresent();

        if (!exists) {
            locations.save(new MarketLocation(
                    null,
                    market.id(),
                    city,
                    district,
                    latitude,
                    longitude,
                    null,
                    true,
                    false,   // modelled: no observations yet
                    Instant.now()));
        }

        out.add(new LocationSnapshot(city, district, latitude, longitude));
    }

    private void writeSnapshot(List<MarketSnapshot> snapshots) {
        try {
            Files.createDirectories(SNAPSHOT.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    SNAPSHOT.toFile(),
                    new Snapshot(Instant.now(), ATTRIBUTION, snapshots));
            log.info("Snapshot written to {}", SNAPSHOT.toAbsolutePath());
        } catch (Exception e) {
            log.error("Could not write snapshot: {}", e.getMessage());
        }
    }

    record Snapshot(Instant importedAt, String attribution, List<MarketSnapshot> markets) {}

    record MarketSnapshot(String code, String name, String currency, String defaultAreaUnit,
                          String locale, List<String> validSources,
                          List<LocationSnapshot> locations) {}

    record LocationSnapshot(String city, String district, double latitude, double longitude) {}
}