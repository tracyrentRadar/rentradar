package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.domain.RentalMarket;
import com.rentradar.backend.domain.type.Area;
import com.rentradar.backend.domain.type.MonetaryAmount;
import com.rentradar.backend.repository.PropertyListingRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
public class PropertyService {

    private final PropertyListingRepository properties;
    private final LocationResolver locationResolver;

    public PropertyService(
            PropertyListingRepository properties,
            LocationResolver locationResolver) {

        this.properties = properties;
        this.locationResolver = locationResolver;
    }

    public PropertyListing create(NewPropertyCommand command, String ownerId) {
        RentalMarket market = locationResolver.requireMarket(command.marketCode());

        // Validate before any write, so a rejected listing leaves nothing behind.
        requireMarketCurrency(market, command.currency());
        requireKnownSource(market, command.source());

        MarketLocation location = locationResolver.resolve(
                market, command.city(), command.district());

        Instant now = Instant.now();
        Instant postedAt = command.postedAt() == null ? now : command.postedAt();

        if (postedAt.isAfter(now)) {
            throw new IllegalArgumentException("postedAt must not be in the future");
        }

        return properties.save(new PropertyListing(
                null,
                ownerId,
                market.code(),
                location.id(),
                command.title(),
                command.bedrooms(),
                command.bathrooms(),
                command.toilets(),
                command.parkingSpaces(),
                normaliseType(command.propertyType()),
                new Area(command.size(), command.sizeUnit()),
                new MonetaryAmount(command.price(), command.currency().toUpperCase(Locale.ROOT)),
                command.furnished(),
                normaliseAmenities(command.amenities()),
                // One spelling per source, so the corpus does not split on casing.
                command.source().toLowerCase(Locale.ROOT),
                postedAt,
                true,
                now));
    }

    /**
     * Binding rule 2. A listing priced in a currency its market does not use is
     * a data error, and accepting it would corrupt every aggregate downstream.
     */
    private static void requireMarketCurrency(RentalMarket market, String currency) {
        if (!market.currency().equalsIgnoreCase(currency)) {
            throw new IllegalArgumentException(
                    "Market " + market.code() + " prices in " + market.currency()
                            + ", not " + currency);
        }
    }

    /** Provenance is a thesis requirement, so an unknown source cannot enter the corpus. */
    private static void requireKnownSource(RentalMarket market, String source) {
        boolean known = market.validSources().stream()
                .anyMatch(valid -> valid.equalsIgnoreCase(source));

        if (!known) {
            throw new IllegalArgumentException(
                    "Unknown source '" + source + "' for market " + market.code()
                            + ". Valid sources: " + market.validSources());
        }
    }

    /**
     * Blank is not a property type, it is a missing one, and the difference
     * matters downstream: the model has a column for absent and no column for
     * empty string.
     */
    private static String normaliseType(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Trimmed, de-duplicated case-insensitively, blanks dropped. Two listings
     * that both offer air conditioning must produce the same feature whether the
     * agent typed "Air Conditioning" or "air conditioning ".
     */
    private static List<String> normaliseAmenities(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        var seen = new java.util.LinkedHashMap<String, String>();
        for (String a : raw) {
            if (a == null) {
                continue;
            }
            String trimmed = a.trim();
            if (!trimmed.isEmpty()) {
                seen.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
            }
        }
        return List.copyOf(seen.values());
    }
}