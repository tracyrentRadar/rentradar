package com.rentradar.backend.config;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.ml.ModelStage;
import com.rentradar.backend.ml.ModelType;
import com.rentradar.backend.repository.ModelArtefactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.Map;

/**
 * Registers one stub artefact per model type so the prediction path has
 * something to resolve while the real models are being trained.
 *
 * <p>The version string must begin "stub". That prefix is the contract with the
 * inference tier: it is the single signal that no artefact file is expected on
 * disk. Any other version makes the Python side look for a .joblib and refuse
 * when it is missing, which is the behaviour you want once training starts.
 *
 * <p>Idempotent, and off by default in any environment that does not ask for it.
 * A stub quietly surviving into production would be the worst outcome here, so
 * it takes a deliberate property to create one.
 */
@Configuration
@ConditionalOnProperty(name = "rentradar.ml.seed-stubs", havingValue = "true")
public class StubModelSeeder {

    private static final Logger log = LoggerFactory.getLogger(StubModelSeeder.class);
    private static final String STUB_VERSION = "stub-1.0.0";
    private static final String MARKET = "GH";

    @Bean
    ApplicationRunner seedStubArtefacts(ModelArtefactRepository registry) {
        return args -> {
            ModelStage stage = stubStage();
            int created = 0;

            // One per type the system knows about, so adding a fourth model type
            // later does not leave a hole the prediction path falls into.
            for (ModelType type : ModelType.values()) {
                if (registry.findByMarketIdAndModelTypeAndStage(MARKET, type, stage).isPresent()) {
                    continue;
                }
                registry.save(new ModelArtefact(
                        null,
                        MARKET,
                        type,
                        STUB_VERSION,
                        stage,
                        null,                 // no artefact file; the stub is in-process
                        Map.of(),             // no metrics, because nothing was measured
                        null,                 // never trained
                        Instant.now()));
                created++;
            }

            if (created > 0) {
                log.warn("Seeded {} STUB model artefacts for market {} at stage {}. "
                                + "Predictions will be plausible, not trained, and every response "
                                + "says so. Remove rentradar.ml.seed-stubs once real models exist.",
                        created, MARKET, stage);
            }
        };
    }

    /**
     * Picks a sensible stage without hardcoding a constant name, because the
     * enum is yours and I would rather this compile against whatever you called
     * them than guess and break your build.
     */
    private static ModelStage stubStage() {
        for (String preferred : new String[]{"STUB", "CANDIDATE", "PRODUCTION", "CHAMPION"}) {
            for (ModelStage s : ModelStage.values()) {
                if (s.name().equalsIgnoreCase(preferred)) {
                    return s;
                }
            }
        }
        return ModelStage.values()[0];
    }
}