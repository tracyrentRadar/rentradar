package com.rentradar.backend.repository;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.ml.ModelStage;
import com.rentradar.backend.ml.ModelType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ModelArtefactRepository extends MongoRepository<ModelArtefact, String> {

    Optional<ModelArtefact> findByMarketIdAndModelTypeAndStage(
            String marketId, ModelType modelType, ModelStage stage);

    List<ModelArtefact> findByMarketId(String marketId);
}