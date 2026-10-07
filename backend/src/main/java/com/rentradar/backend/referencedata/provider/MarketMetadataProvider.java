package com.rentradar.backend.referencedata.provider;

import java.util.Optional;

/** Resolves a country code to the metadata a rental market needs. */
public interface MarketMetadataProvider {

    Optional<MarketMetadata> lookup(String countryIsoCode);

    record MarketMetadata(
            String countryName,
            String currencyCode,
            String locale
    ) {}
}