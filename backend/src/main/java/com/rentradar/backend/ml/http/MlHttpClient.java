package com.rentradar.backend.ml.http;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.type.RiskLevel;
import com.rentradar.backend.domain.type.TrendDirection;
import com.rentradar.backend.ml.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Calls the Python inference tier over HTTP.
 *
 * <p>The contract this client enforces, beyond moving JSON: the artefact the
 * caller resolved is the artefact that runs. The request names it and the
 * response echoes back what actually ran; if those disagree the call fails
 * rather than returning a number. Serving a stub where production was asked for,
 * or one market's model for another's question, is the failure mode that would
 * otherwise be invisible, and invisible wrong answers are worse than loud ones.
 */
@Component
@Primary
public class MlHttpClient implements MlClient {

    private static final Logger log = LoggerFactory.getLogger(MlHttpClient.class);

    private final RestClient http;

    public MlHttpClient(MlClientProperties props, RestClient.Builder builder) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) props.connectTimeout().toMillis());
        factory.setReadTimeout((int) props.readTimeout().toMillis());
        this.http = builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public PriceEstimate estimatePrice(FeatureVector features, ModelArtefact model) {
        long started = System.nanoTime();
        PriceResponse body = post("/v1/price/estimate",
                new PredictRequest(features.specVersion(), ref(model), features),
                PriceResponse.class, model);
        assertSameArtefact(model, body.modelRan());
        return new PriceEstimate(
                body.predicted(), body.lower(), body.upper(),
                body.currency(), model, millisSince(started));
    }

    @Override
    public FraudAssessment assessFraud(FeatureVector features, ModelArtefact model) {
        long started = System.nanoTime();
        FraudResponse body = post("/v1/fraud/assess",
                new PredictRequest(features.specVersion(), ref(model), features),
                FraudResponse.class, model);
        assertSameArtefact(model, body.modelRan());
        return new FraudAssessment(
                body.riskScore(), toRiskLevel(body.riskLevel()),
                body.reconstructionError(), body.principalFactor(),
                model, millisSince(started));
    }

    @Override
    public TrendForecast forecastTrend(
            String marketId, String locationId, int horizonDays, ModelArtefact model) {

        if (!model.marketId().equals(marketId)) {
            // Caught here rather than at the far end, because this one is a bug
            // in the caller, not a disagreement between tiers.
            throw new MlServiceException(
                    "artefact market " + model.marketId() + " does not match requested market "
                            + marketId + "; a model trained on one market must not answer for another",
                    false);
        }
        long started = System.nanoTime();
        ForecastResponse body = post("/v1/forecast/trend",
                new ForecastRequest(marketId, locationId, horizonDays, ref(model)),
                ForecastResponse.class, model);
        assertSameArtefact(model, body.modelRan());

        List<TrendForecast.Point> series = body.series().stream()
                .map(p -> new TrendForecast.Point(p.day(), p.value(), p.lower(), p.upper()))
                .toList();
        return new TrendForecast(
                series, toDirection(body.direction()),
                body.meanAbsoluteError(), model, millisSince(started));
    }

    // ------------------------------------------------------------------ plumbing

    private <T> T post(String path, Object request, Class<T> type, ModelArtefact model) {
        try {
            T body = http.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(status -> status.value() == 422, (req, res) -> {
                        // The inference tier refused on purpose: unknown market,
                        // currency it will not convert, schema violation. Carry
                        // the detail through rather than flattening it.
                        throw new MlServiceException(
                                "inference tier refused " + path + ": "
                                        + new String(res.getBody().readAllBytes()), true);
                    })
                    .body(type);
            if (body == null) {
                throw new MlServiceException("empty body from " + path, false);
            }
            return body;
        } catch (MlServiceException e) {
            throw e;
        } catch (RestClientException e) {
            throw new MlServiceException(
                    "inference tier unreachable for " + model.qualifiedVersion()
                            + " at " + path, e);
        }
    }

    /**
     * The served artefact must be the requested artefact. Stage is compared by
     * name so this stays correct whatever the enum constants are called.
     */
    private void assertSameArtefact(ModelArtefact asked, ArtefactRef got) {
        if (got == null) {
            throw new MlServiceException(
                    "inference tier did not say which model it ran; refusing the answer", false);
        }
        boolean same = asked.marketId().equals(got.marketId())
                && asked.modelType().name().equalsIgnoreCase(got.modelType())
                && asked.version().equals(got.version())
                && asked.stage().name().equalsIgnoreCase(got.stage());
        if (!same) {
            throw new MlServiceException(
                    "asked for " + asked.qualifiedVersion() + " at stage " + asked.stage()
                            + " but the inference tier ran " + got.marketId() + ":" + got.modelType()
                            + ":" + got.version() + " at stage " + got.stage()
                            + "; the two tiers disagree and no answer from either is trustworthy",
                    false);
        }
        if (Boolean.TRUE.equals(got.isStub())) {
            log.warn("prediction served by a STUB artefact {} - plausible, not trained",
                    asked.qualifiedVersion());
        }
    }

    private static ArtefactRef ref(ModelArtefact m) {
        return new ArtefactRef(m.marketId(), m.modelType().name(), m.version(),
                m.stage().name(), m.artefactUri(), null);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static RiskLevel toRiskLevel(String raw) {
        try {
            return RiskLevel.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MlServiceException("unrecognised risk level from inference tier: " + raw, false);
        }
    }

    private static TrendDirection toDirection(String raw) {
        try {
            return TrendDirection.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new MlServiceException("unrecognised trend direction from inference tier: " + raw, false);
        }
    }

    // --------------------------------------------------------------- wire shapes
    // Deliberately separate from the domain records. The wire is allowed to
    // change shape without dragging the domain with it.

    record ArtefactRef(String marketId, String modelType, String version,
                       String stage, String artefactUri, Boolean isStub) {}

    record PredictRequest(int specVersion, ArtefactRef model, FeatureVector features) {}

    record ForecastRequest(String marketId, String locationId, int horizonDays,
                           ArtefactRef model) {}

    record PriceResponse(BigDecimal predicted, BigDecimal lower, BigDecimal upper,
                         String currency, ArtefactRef modelRan) {}

    record FraudResponse(double riskScore, String riskLevel, double reconstructionError,
                         String principalFactor, ArtefactRef modelRan) {}

    record ForecastPointDto(int day, BigDecimal value, BigDecimal lower, BigDecimal upper) {}

    record ForecastResponse(List<ForecastPointDto> series, String direction,
                            double meanAbsoluteError, ArtefactRef modelRan) {}
}