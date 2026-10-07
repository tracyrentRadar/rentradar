package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.ml.FeatureVector;
import org.springframework.stereotype.Component;

/**
 * The single place a feature vector is built for serving. The training pipeline
 * reads the same spec version, which is what keeps training and serving from
 * drifting apart.
 *
 * <p>Nothing here invents a value. Where the listing is silent the vector is
 * silent, and the model treats the absence as the information it is.
 */
@Component
public class FeatureAssembler {

    public FeatureVector assemble(PropertyListing listing, MarketLocation location) {
        return new FeatureVector(
                FeatureVector.CURRENT_SPEC_VERSION,
                listing.marketId(),
                listing.locationId(),
                location.localityKey(),
                listing.bedrooms(),
                listing.bathrooms(),
                listing.toilets(),
                listing.parkingSpaces(),
                listing.propertyType(),
                listing.size().inSquareMetres(),
                listing.furnished(),
                listing.amenities(),
                listing.source(),
                listing.listedPrice().amount(),
                listing.listedPrice().currency());
    }
}