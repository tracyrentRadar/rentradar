package com.rentradar.backend.domain;

import com.rentradar.backend.ml.ModelStage;
import com.rentradar.backend.ml.ModelType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A registered model artefact. Binding rule 4 made mechanical: resolution is on
 * (marketId, modelType, stage), so a model trained on one market can never serve
 * another.
 *
 * metrics carries whatever the training run reported, R2 and MAE for price, F1
 * for fraud. Stored here so a promotion decision is auditable after the fact.
 */
@Document(collection = "model_registry")
@CompoundIndex(
        name = "market_type_stage_unique",
        def = "{'marketId': 1, 'modelType': 1, 'stage': 1}",
        unique = true
)
public record ModelArtefact(

        @Id String id,

        @Indexed String marketId,

        ModelType modelType,

        String version,

        ModelStage stage,

        /** Where the serving tier loads it from. Null for the in-process stub. */
        String artefactUri,

        Map<String, Double> metrics,

        Instant trainedAt,

        Instant registeredAt
) {
    public ModelArtefact {
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(modelType, "modelType must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        Objects.requireNonNull(registeredAt, "registeredAt must not be null");

        if (version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
    }

    /** What gets written onto every prediction. */
    public String qualifiedVersion() {
        return marketId + ":" + modelType + ":" + version;
    }
}