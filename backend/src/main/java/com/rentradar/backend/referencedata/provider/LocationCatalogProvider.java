package com.rentradar.backend.referencedata.provider;

import java.util.List;

/**
 * Discovers the geography of a market rather than being told it.
 * Implementations query a geographic database; the interface exists so the
 * source can be replaced without touching the importer.
 */
public interface LocationCatalogProvider {

    /** Every city and town in a country, by ISO 3166-1 alpha-2 code. */
    List<DiscoveredPlace> discoverCities(String countryIsoCode);

    /** Every suburb, neighbourhood or quarter within reach of a city centre. */
    List<DiscoveredPlace> discoverLocalities(DiscoveredPlace city, int radiusMetres);

    record DiscoveredPlace(
            String name,
            String placeType,
            double latitude,
            double longitude,
            Integer population
    ) {}
}
