package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.AreaUnit;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * A rental market the platform serves. A market owns its currency, its default area
 * unit, its locale and the set of listing sources valid within it. Models are trained
 * and resolved per market: an artefact trained for one market must never serve another.
 */
@Document(collection = "markets")
public record RentalMarket(

        @Id String id,

        @Indexed(unique = true) String code,

        String name,

        String currency,

        AreaUnit defaultAreaUnit,

        String locale,

        Set<String> validSources,

        boolean active,

        Instant createdAt
) {

    public RentalMarket {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(defaultAreaUnit, "defaultAreaUnit must not be null");
        Objects.requireNonNull(locale, "locale must not be null");
        Objects.requireNonNull(validSources, "validSources must not be null");

        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (!code.equals(code.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("code must be uppercase, was " + code);
        }
        // Rejects an invalid ISO 4217 code at construction.
        Currency.getInstance(currency);
        // Rejects a malformed BCP 47 tag.
        if (Locale.forLanguageTag(locale).toLanguageTag().equals("und")) {
            throw new IllegalArgumentException("locale is not a valid BCP 47 tag: " + locale);
        }

        validSources = Set.copyOf(validSources);
    }

    public boolean allowsSource(String source) {
        return validSources.contains(source);
    }
}