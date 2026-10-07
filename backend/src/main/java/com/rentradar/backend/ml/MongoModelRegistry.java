package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.repository.ModelArtefactRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Champions are cached because they are read on every prediction and change only
 * on deploy. Challengers are not cached: they are read on a background path where
 * freshness matters more than latency.
 */
@Component
public class MongoModelRegistry implements ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(MongoModelRegistry.class);

    private final ModelArtefactRepository artefacts;
    private final Map<String, ModelArtefact> champions = new ConcurrentHashMap<>();

    public MongoModelRegistry(ModelArtefactRepository artefacts) {
        this.artefacts = artefacts;
    }

    @Override
    public ModelArtefact champion(ModelType type, String marketId) {
        return champions.computeIfAbsent(key(type, marketId), ignored ->
                artefacts.findByMarketIdAndModelTypeAndStage(marketId, type, ModelStage.CHAMPION)
                        .orElseThrow(() -> new UnknownModelException(
                                "No CHAMPION " + type + " model registered for market " + marketId)));
    }

    @Override
    public Optional<ModelArtefact> challenger(ModelType type, String marketId) {
        return artefacts.findByMarketIdAndModelTypeAndStage(
                marketId, type, ModelStage.CHALLENGER);
    }

    @Override
    public void reload() {
        champions.clear();
        log.info("Model registry cache cleared");
    }

    private static String key(ModelType type, String marketId) {
        return marketId + ":" + type;
    }
}