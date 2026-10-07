package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;

/**
 * The boundary between the gateway and the inference tier.
 *
 * Every method takes the artefact to run. The caller resolves it from the
 * registry, which is what lets the same client serve a champion on the request
 * path and a challenger on a background path with no branching in here.
 */
public interface MlClient {

    PriceEstimate estimatePrice(FeatureVector features, ModelArtefact model);

    FraudAssessment assessFraud(FeatureVector features, ModelArtefact model);

    TrendForecast forecastTrend(
            String marketId, String locationId, int horizonDays, ModelArtefact model);
}