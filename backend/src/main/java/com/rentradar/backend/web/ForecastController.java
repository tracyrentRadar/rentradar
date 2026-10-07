package com.rentradar.backend.web;

import com.rentradar.backend.service.ForecastService;
import com.rentradar.backend.web.dto.ForecastResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/forecasts")
public class ForecastController {

    private final ForecastService forecasts;

    public ForecastController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    /** 404 when no forecast has been generated yet, which is honest. */
    @GetMapping("/{locationId}")
    public ForecastResponse byLocation(@PathVariable String locationId) {
        return forecasts.latestFor(locationId)
                .map(ForecastResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No forecast available for that location yet"));
    }
}