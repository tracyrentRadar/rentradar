package com.rentradar.backend.referencedata.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves country name, ISO 4217 currency and a BCP 47 locale from REST Countries v5.
 *
 * Four behaviours of this service shape the implementation: requests are
 * authenticated with a bearer key; the host is api.restcountries.com rather than
 * the documentation site; lookup by country code is a path segment
 * (/codes.alpha_2/GH) rather than a search parameter; and results are wrapped two
 * levels deep, under data.objects.
 *
 * Only the three fields the platform needs are requested, which keeps the response
 * small: the full country document includes flags, translations and map links.
 */
@Component
public class RestCountriesMarketProvider implements MarketMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(RestCountriesMarketProvider.class);
    private static final String BASE_URL = "https://api.restcountries.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestCountriesMarketProvider(
            ObjectMapper objectMapper,
            @Value("${rentradar.restcountries.api-key}") String apiKey) {

        this.objectMapper = objectMapper;

        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(httpClient))
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "RentRadar/0.1 (final year project)")
                .build();
    }

    @Override
    public Optional<MarketMetadata> lookup(String countryIsoCode) {
        String wanted = countryIsoCode.toUpperCase(Locale.ROOT);

        try {
            String body = restClient.get()
                    .uri("/countries/v5/codes.alpha_2/{code}?response_fields=names,codes,currencies,languages",
                            wanted)
                    .retrieve()
                    .body(String.class);

            if (body == null) {
                log.error("Empty response for {}", wanted);
                return Optional.empty();
            }

            JsonNode country = selectCountry(objectMapper.readTree(body), wanted);

            if (country == null) {
                log.error("Could not locate {} in response. Raw: {}", wanted, truncate(body));
                return Optional.empty();
            }

            String name = country.path("names").path("common").asText(null);
            String currency = extractCurrency(country);
            String language = extractLanguage(country);

            if (name == null || currency == null) {
                log.error("Missing name or currency for {}. Node was: {}",
                        wanted, truncate(country.toString()));
                return Optional.empty();
            }

            log.info("Resolved {}: {}, {}, {}", wanted, name, currency, language + "-" + wanted);
            return Optional.of(new MarketMetadata(name, currency, language + "-" + wanted));

        } catch (Exception e) {
            log.error("Country lookup failed for {}: {}", wanted, e.getMessage());
            return Optional.empty();
        }
    }

    /** Results are nested two levels: { "data": { "objects": [ ... ] } }. */
    private JsonNode selectCountry(JsonNode root, String wantedCode) {
        JsonNode objects = root.path("data").path("objects");

        if (!objects.isArray() || objects.isEmpty()) {
            return null;
        }
        for (JsonNode candidate : objects) {
            if (wantedCode.equalsIgnoreCase(
                    candidate.path("codes").path("alpha_2").asText(null))) {
                return candidate;
            }
        }
        // A code lookup returns one match, so fall back to it rather than failing.
        return objects.get(0);
    }

    /** currencies is an array of objects carrying the ISO 4217 code. */
    private String extractCurrency(JsonNode country) {
        JsonNode currencies = country.path("currencies");
        if (currencies.isArray() && !currencies.isEmpty()) {
            return currencies.get(0).path("code").asText(null);
        }
        return null;
    }

    /** languages is an array; bcp47 is already the two-letter subtag we need. */
    private String extractLanguage(JsonNode country) {
        JsonNode languages = country.path("languages");
        if (languages.isArray() && !languages.isEmpty()) {
            JsonNode first = languages.get(0);
            String bcp47 = first.path("bcp47").asText(null);
            if (bcp47 != null && !bcp47.isBlank()) {
                return bcp47;
            }
            String iso1 = first.path("iso639_1").asText(null);
            if (iso1 != null && !iso1.isBlank()) {
                return iso1;
            }
        }
        return "en";
    }

    private String truncate(String s) {
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}