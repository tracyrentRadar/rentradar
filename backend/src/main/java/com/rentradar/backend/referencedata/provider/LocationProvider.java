package com.rentradar.backend.referencedata.provider;

import java.util.Optional;

/**
 * Resolves a named locality to coordinates. Implementations wrap a geocoding
 * service; the interface exists so the service can be replaced without touching
 * the importer.
 */
public interface LocationProvider {

    Optional<Coordinates> resolve(String city, String district, String countryName);

    record Coordinates(double latitude, double longitude, String resolvedName) {}
}