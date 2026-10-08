package com.rentradar.backend.config;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.ml.ModelStage;
import com.rentradar.backend.ml.ModelType;
import com.rentradar.backend.repository.ModelArtefactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the trained artefacts as CHAMPION, replacing the stubs.
 *
 * <p>The unique index on (marketId, modelType, stage) means there is one
 * CHAMPION per type, so this replaces in place rather than inserting. A stub
 * left in that slot would make the whole inference tier pointless: the gateway
 * would resolve the stub, ask for it by name, and the Python side would
 * dutifully return plausible fiction.
 *
 * <p>The metrics are the measured ones from ml/artifacts/models/registry.json.
 * They are stored on the artefact so a promotion decision stays auditable, and
 * they must be updated here whenever the export is re-run with different
 * numbers. Two places, one commit.
 *
 * <p>Deliberately gated on a property. Writing to the model registry at startup
 * is not something that should happen because somebody ran the app.
 */
@Configuration
@ConditionalOnProperty(name = "rentradar.ml.register-models", havingValue = "true")
public class RealModelRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RealModelRegistrar.class);
    private static final String MARKET = "GH";

    @Bean
    ApplicationRunner registerTrainedArtefacts(
            ModelArtefactRepository registry,
            @Value("${rentradar.ml.model-version}") String version) {

        return args -> {
            if (version.toLowerCase().startsWith("stub")) {
                throw new IllegalStateException(
                        "rentradar.ml.model-version is '" + version + "'. A version "
                                + "beginning 'stub' tells the inference tier not to load an "
                                + "artefact, which is the opposite of what registering real "
                                + "models means.");
            }

            register(registry, ModelType.PRICE, version,
                    "file:ml/artifacts/models/price_gb.joblib",
                    metrics(
                            "r2_cedis", 0.706,
                            "r2_log", 0.852,
                            "mae_ghs", 5691.0,
                            "mape_pct", 33.9,
                            "within_20pct", 42.4,
                            "interval_coverage_pct", 89.4));

            register(registry, ModelType.FRAUD, version,
                    "file:ml/artifacts/models/fraud_lstm.keras",
                    // No F1. There are no labels yet, and inventing one would be
                    // worse than the gap. Fill this in after the hand labelling.
                    metrics("price_ratio_correlation", -0.150));

            register(registry, ModelType.TREND, version,
                    "file:ml/artifacts/models/forecast_moving_average.json",
                    metrics(
                            "mae_ghs", 2297.0,
                            "mape_pct", 11.27,
                            "walk_forward_origins", 9.0));
        };
    }

    private void register(ModelArtefactRepository registry, ModelType type,
                          String version, String uri, Map<String, Double> metrics) {

        var existing = registry.findByMarketIdAndModelTypeAndStage(
                MARKET, type, ModelStage.CHAMPION);

        String id = existing.map(ModelArtefact::id).orElse(null);
        existing.ifPresent(a -> {
            if (a.version().equalsIgnoreCase(version)) {
                return;
            }
            log.warn("Replacing CHAMPION {} {} with {}", type, a.version(), version);
        });

        if (existing.map(a -> a.version().equalsIgnoreCase(version)).orElse(false)) {
            log.info("CHAMPION {} already at {}", type, version);
            return;
        }

        Instant now = Instant.now();
        registry.save(new ModelArtefact(
                id, MARKET, type, version, ModelStage.CHAMPION, uri, metrics, now, now));
        log.info("Registered CHAMPION {} version {}", type, version);
    }

    private static Map<String, Double> metrics(Object... pairs) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], ((Number) pairs[i + 1]).doubleValue());
        }
        return out;
    }
}