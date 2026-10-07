package com.rentradar.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePredictionRequest(
        @NotBlank String propertyId
) {}