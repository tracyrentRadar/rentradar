package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;

import java.math.BigDecimal;

public record PriceEstimate(
        BigDecimal predicted,
        BigDecimal lower,
        BigDecimal upper,
        String currency,
        ModelArtefact model,
        long latencyMs
) {}