package com.rentradar.backend.web;

import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.repository.PricePredictionRepository;
import com.rentradar.backend.security.AuthenticatedUser;
import com.rentradar.backend.service.PredictionOrchestrator;
import com.rentradar.backend.web.dto.CreatePredictionRequest;
import com.rentradar.backend.web.dto.PredictionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionController {

    private final PredictionOrchestrator orchestrator;
    private final PricePredictionRepository predictions;

    public PredictionController(
            PredictionOrchestrator orchestrator,
            PricePredictionRepository predictions) {

        this.orchestrator = orchestrator;
        this.predictions = predictions;
    }

    @PostMapping
    public PredictionResponse create(
            @Valid @RequestBody CreatePredictionRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return PredictionResponse.from(
                orchestrator.predict(request.propertyId(), principal.userId()));
    }

    /**
     * Ownership is in the query, not a check after the fetch. A post-fetch check
     * is how insecure direct object reference bugs get written.
     */
    @GetMapping("/{id}")
    public PricePrediction byId(
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return predictions.findByIdAndUserId(id, principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @GetMapping
    public List<PricePrediction> mine(@AuthenticationPrincipal AuthenticatedUser principal) {
        return predictions.findByUserIdOrderByCreatedAtDesc(principal.userId());
    }
}