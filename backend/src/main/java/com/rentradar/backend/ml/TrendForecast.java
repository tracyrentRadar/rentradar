package com.rentradar.backend.ml;

import com.rentradar.backend.domain.ModelArtefact;
import com.rentradar.backend.domain.type.TrendDirection;

import java.math.BigDecimal;
import java.util.List;

public record TrendForecast(
        List<Point> series,
        TrendDirection direction,
        double meanAbsoluteError,
        ModelArtefact model,
        long latencyMs
) {
    public record Point(int day, BigDecimal value, BigDecimal lower, BigDecimal upper) {}
}