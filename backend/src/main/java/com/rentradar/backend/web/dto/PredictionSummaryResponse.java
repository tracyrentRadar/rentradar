package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.domain.type.PriceVerdict;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the estimate history, as the Home and History screens draw it:
 *
 * <pre>2 bed, East Legon    asked 4,500 · fair 4,356    AT MARKET</pre>
 *
 * <p>Three of those five facts live on the property, not the prediction, which
 * is why this exists. Returning the stored {@code PricePrediction} straight to
 * the client gave the screen a fair price and a verdict and nothing to label
 * them with, so the app would have had to fetch each property separately, one
 * request per row.
 *
 * <p>It also stops the domain record going out over the wire. That record
 * carries {@code userId} and {@code correlationId}, neither of which is the
 * client's business, and welds the JSON contract to the database schema so a
 * field rename becomes a breaking API change.
 *
 * <p>Every property-derived field is nullable on purpose. A listing can be
 * deleted after its estimate was taken. When that happens the estimate is still
 * real and still belongs in the history, so the row survives with nulls where
 * the description used to be, and the screen shows what it has. Defaulting
 * bedrooms to zero would put "0 bed" on screen, which is a statement the system
 * has no basis for.
 */
public record PredictionSummaryResponse(

        String id,

        String propertyId,

        /** Null when the listing behind this estimate no longer exists. */
        Integer bedrooms,

        String city,

        /** The district, which is what the screens call the locality. */
        String locality,

        /** What the landlord or agent was asking. */
        BigDecimal askedPrice,

        /** What the model estimated. */
        BigDecimal fairPrice,

        String currency,

        PriceVerdict verdict,

        String modelVersion,

        Instant createdAt
) {

    public static PredictionSummaryResponse of(PricePrediction prediction,
                                               PropertyListing property,
                                               MarketLocation location) {

        return new PredictionSummaryResponse(
                prediction.id(),
                prediction.propertyId(),
                property == null ? null : property.bedrooms(),
                location == null ? null : location.city(),
                location == null ? null : location.district(),
                property == null || property.listedPrice() == null
                        ? null
                        : property.listedPrice().amount(),
                prediction.predictedPrice().amount(),
                prediction.predictedPrice().currency(),
                prediction.verdict(),
                prediction.modelVersion(),
                prediction.createdAt());
    }
}