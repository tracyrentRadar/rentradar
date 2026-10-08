package com.rentradar.backend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.RentalMarket;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.RentalMarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Seeds the GH market and its localities from the trained feature spec.
 *
 * <p>Why this exists alongside {@code ReferenceDataImporter}. That importer
 * discovers geography from Overpass and country metadata from RestCountries,
 * which is the right design and stays. It needs a RestCountries key and a
 * working connection to two third parties, neither of which can be relied on
 * during a demonstration, and its last run imported nothing.
 *
 * <p>This seeds from {@code accra-localities.json}, generated from the model's
 * own {@code feature_spec.json}. That has a property benefit beyond
 * convenience: the only localities a user can choose are the ones the model
 * holds training data for, so the system cannot be asked for an estimate it
 * has no basis to make. A locality with fewer than the spec's minimum
 * observations is not offered at all rather than being answered badly.
 *
 * <p>Two honest limitations, both of which belong in the write up. The
 * coordinates are the Accra city centroid, not per locality, because no
 * geocoding ran. Nothing in the models uses coordinates, so this affects
 * display only. And locality names are stored exactly as the spec holds them,
 * including casing, because the name is the key the model matches on and
 * prettifying it here would silently break the match.
 */
@Configuration
@ConditionalOnProperty(name = "rentradar.reference-data.seed-localities", havingValue = "true")
public class LocalitySeeder {

    private static final Logger log = LoggerFactory.getLogger(LocalitySeeder.class);
    private static final Path SEED = Path.of("reference-data", "accra-localities.json");

    // Accra city centroid. Approximate and uniform across localities, see above.
    private static final double LAT = 5.6037;
    private static final double LON = -0.1870;

    @Bean
    ApplicationRunner seedLocalities(RentalMarketRepository markets,
                                     MarketLocationRepository locations,
                                     ReferenceDataProperties properties,
                                     ObjectMapper mapper) {
        return args -> {
            if (!Files.exists(SEED)) {
                log.warn("No {} on disk, nothing seeded. Generate it from the "
                        + "feature spec first.", SEED);
                return;
            }

            Seed seed = mapper.readValue(SEED.toFile(), Seed.class);
            if (seed.localities() == null || seed.localities().isEmpty()) {
                log.warn("{} holds no localities, nothing seeded", SEED);
                return;
            }

            RentalMarket market = markets.findByCode(seed.market()).orElseGet(() -> {
                log.info("Creating market {}", seed.market());
                return markets.save(new RentalMarket(
                        null,
                        seed.market(),
                        "Ghana",
                        "GHS",
                        properties.defaultAreaUnit(),
                        "en-GH",
                        Set.copyOf(properties.validSources()),
                        true,
                        Instant.now()));
            });

            int created = 0;

            // The city itself, so a user can pick Accra without naming a district.
            if (save(locations, market, seed.city(), seed.city())) {
                created++;
            }
            for (String district : seed.localities()) {
                if (district != null && !district.isBlank()
                        && save(locations, market, seed.city(), district)) {
                    created++;
                }
            }

            log.info("Seeded market {} with {} new locations from spec {} "
                            + "({} localities in the file)",
                    market.code(), created, seed.specVersion(), seed.localities().size());
        };
    }

    private boolean save(MarketLocationRepository locations, RentalMarket market,
                         String city, String district) {
        if (locations.findByMarketIdAndCityAndDistrict(market.id(), city, district)
                .isPresent()) {
            return false;
        }
        locations.save(new MarketLocation(
                null, market.id(), city, district, LAT, LON, null,
                true,
                // modelled: the whole point of seeding from the spec is that
                // every one of these has training data behind it.
                true,
                Instant.now()));
        return true;
    }

    record Seed(String market, String city, String specVersion, List<String> localities) {}
}