package com.rentradar.backend.ml;

import java.math.BigDecimal;
import java.util.List;

/**
 * The serving-side feature vector. specVersion is carried so a stored prediction
 * records not only which model ran but which feature definition produced its
 * input. Training and serving reading the same spec is what prevents skew.
 *
 * <p>Nullability is load-bearing. furnished is a Boolean rather than a primitive
 * because three quarters of observed listings say nothing about furnishing, and
 * "nobody said" is not the same fact as "it is unfurnished". Collapsing them
 * trains the model on two populations wearing one label. Same for toilets,
 * parking and property type: absent is a value the model uses, not a hole to be
 * filled with zero.
 *
 * <p>Every field here appears in feature_spec.json on the Python side. Add one
 * to either and you add it to both, and CURRENT_SPEC_VERSION goes up.
 */
public record FeatureVector(
        int specVersion,
        String marketId,
        String locationId,
        String localityKey,
        int bedrooms,
        int bathrooms,
        Integer toilets,
        Integer parkingSpaces,
        String propertyType,
        BigDecimal sizeSquareMetres,
        Boolean furnished,
        List<String> amenities,
        String source,
        BigDecimal listedPrice,
        String currency
) {
    public static final int CURRENT_SPEC_VERSION = 1;

    public FeatureVector {
        amenities = amenities == null ? List.of() : List.copyOf(amenities);
    }
}