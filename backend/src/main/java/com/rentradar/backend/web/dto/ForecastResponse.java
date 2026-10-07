package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.MarketTrendForecast;
import com.rentradar.backend.domain.type.TrendDirection;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ForecastResponse(
        String locationId,
        String marketId,
        int horizonDays,
        TrendDirection direction,
        String currency,
        double meanAbsoluteError,
        String modelVersion,
        Instant generatedAt,
        List<Point> series
) {
    public record Point(int day, BigDecimal value, BigDecimal lower, BigDecimal upper) {}

    public static ForecastResponse from(MarketTrendForecast forecast) {
        String currency = forecast.series().isEmpty()
                ? null
                : forecast.series().get(0).value().currency();

        return new ForecastResponse(
                forecast.locationId(),
                forecast.marketId(),
                forecast.horizonDays(),
                forecast.direction(),
                currency,
                forecast.mae(),
                forecast.modelVersion(),
                forecast.generatedAt(),
                forecast.series().stream()
                        .map(p -> new Point(
                                p.day(),
                                p.value().amount(),
                                p.lower().amount(),
                                p.upper().amount()))
                        .toList());
    }
}