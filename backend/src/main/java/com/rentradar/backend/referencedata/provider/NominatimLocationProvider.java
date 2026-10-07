package com.rentradar.backend.referencedata.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Optional;

/**
 * Geocodes localities using OpenStreetMap's Nominatim service.
 *
 * Nominatim's usage policy requires an identifying User-Agent and at most one
 * request per second. Both are honoured here: breaching either results in the
 * client being blocked, so the throttle is a correctness concern, not a courtesy.
 *
 * Data is ODbL licensed and must be attributed as "© OpenStreetMap contributors".
 */
@Component
public class NominatimLocationProvider implements LocationProvider {

    private static final Logger log = LoggerFactory.getLogger(NominatimLocationProvider.class);
    private static final String BASE_URL = "https://nominatim.openstreetmap.org";
    private static final long MIN_INTERVAL_MS = 1_100;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private long lastRequestAt = 0L;

    public NominatimLocationProvider(
            ObjectMapper objectMapper,
            @Value("${rentradar.nominatim.user-agent:RentRadar/0.1 (final year project)}")
            String userAgent) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("User-Agent", userAgent)
                .build();
    }

    @Override
    public Optional<Coordinates> resolve(String city, String district, String countryName) {
        String query = district + ", " + city + ", " + countryName;
        throttle();

        try {
            String uri = UriComponentsBuilder.fromPath("/search")
                    .queryParam("q", query)
                    .queryParam("format", "json")
                    .queryParam("limit", 1)
                    .build()
                    .toUriString();

            String body = restClient.get().uri(uri).retrieve().body(String.class);
            JsonNode results = objectMapper.readTree(body);

            if (!results.isArray() || results.isEmpty()) {
                log.warn("Nominatim returned no match for '{}'", query);
                return Optional.empty();
            }

            JsonNode first = results.get(0);
            // Nominatim returns lat and lon as strings, not numbers.
            double lat = Double.parseDouble(first.get("lat").asText());
            double lon = Double.parseDouble(first.get("lon").asText());
            String name = first.path("display_name").asText(query);

            return Optional.of(new Coordinates(lat, lon, name));

        } catch (Exception e) {
            log.error("Geocoding failed for '{}': {}", query, e.getMessage());
            return Optional.empty();
        }
    }


    /** Enforces the one-request-per-second policy. */
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