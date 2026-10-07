package com.rentradar.backend.referencedata.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates geography from OpenStreetMap via the Overpass API.
 *
 * Overpass is free and requires no account, but it is a shared public service:
 * queries are serialised with a delay and carry an identifying User-Agent, and
 * the import is expected to run rarely, not on every boot.
 *
 * Data is ODbL licensed and must be attributed as "© OpenStreetMap contributors".
 */
@Component
public class OverpassLocationProvider implements LocationCatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(OverpassLocationProvider.class);
    private static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final long MIN_INTERVAL_MS = 2_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private long lastRequestAt = 0L;

    public OverpassLocationProvider(
            ObjectMapper objectMapper,
            @Value("${rentradar.overpass.user-agent:RentRadar/0.1 (final year project)}")
            String userAgent) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(ENDPOINT)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    @Override
    public List<DiscoveredPlace> discoverCities(String countryIsoCode) {
        String query = """
                [out:json][timeout:180];
                area["ISO3166-1"="%s"]["admin_level"="2"]->.country;
                (
                  node(area.country)["place"~"^(city|town)$"]["name"];
                );
                out center;
                """.formatted(countryIsoCode);

        List<DiscoveredPlace> places = execute(query);
        log.info("Discovered {} cities and towns in {}", places.size(), countryIsoCode);
        return places;
    }

    @Override
    public List<DiscoveredPlace> discoverLocalities(DiscoveredPlace city, int radiusMetres) {
        String query = """
                [out:json][timeout:180];
                (
                  node(around:%d,%f,%f)["place"~"^(suburb|neighbourhood|quarter)$"]["name"];
                  way(around:%d,%f,%f)["place"~"^(suburb|neighbourhood|quarter)$"]["name"];
                );
                out center;
                """.formatted(
                radiusMetres, city.latitude(), city.longitude(),
                radiusMetres, city.latitude(), city.longitude());

        List<DiscoveredPlace> places = execute(query);
        log.info("Discovered {} localities within {} m of {}",
                places.size(), radiusMetres, city.name());
        return places;
    }

    private List<DiscoveredPlace> execute(String query) {
        throttle();
        List<DiscoveredPlace> results = new ArrayList<>();

        try {
            String body = restClient.post()
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(query)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            JsonNode elements = root.path("elements");

            for (JsonNode element : elements) {
                JsonNode tags = element.path("tags");
                String name = tags.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }

                // Nodes carry lat/lon directly; ways carry a computed centre.
                double lat;
                double lon;
                if (element.has("lat") && element.has("lon")) {
                    lat = element.get("lat").asDouble();
                    lon = element.get("lon").asDouble();
                } else if (element.has("center")) {
                    lat = element.get("center").get("lat").asDouble();
                    lon = element.get("center").get("lon").asDouble();
                } else {
                    continue;
                }

                results.add(new DiscoveredPlace(
                        name,
                        tags.path("place").asText("unknown"),
                        lat,
                        lon,
                        parsePopulation(tags.path("population").asText(null))));
            }

        } catch (Exception e) {
            log.error("Overpass query failed: {}", e.getMessage());
        }

        return results;
    }

    /** Population is a free-text OSM tag and is frequently absent or malformed. */
    private Integer parsePopulation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long waitFor = MIN_INTERVAL_MS - (now - lastRequestAt);
        if (waitFor > 0) {
            try {
                Thread.sleep(waitFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt = System.currentTimeMillis();
    }
}