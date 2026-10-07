package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;

import java.util.Optional;

public interface ModelRegistry {

    /** Fails loudly. A market with no registered model must not silently borrow another's. */
    ModelArtefact champion(ModelType type, String marketId);

    /** Present only while a challenger is being evaluated in shadow mode. */
    Optional<ModelArtefact> challenger(ModelType type, String marketId);

    /** Drops the resolution cache. Called by POST /admin/models/reload after a deploy. */
    void reload();
}