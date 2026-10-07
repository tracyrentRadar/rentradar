package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.type.RiskLevel;

public record FraudAssessment(
        double riskScore,
        RiskLevel riskLevel,
        double reconstructionError,
        String principalFactor,
        ModelArtefact model,
        long latencyMs
) {}