package com.rentradar.backend.web;

import com.rentradar.backend.security.AuthenticatedUser;
import com.rentradar.backend.service.PredictionHistoryService;
import com.rentradar.backend.service.PredictionOrchestrator;
import com.rentradar.backend.web.dto.CreatePredictionRequest;
import com.rentradar.backend.web.dto.PredictionResponse;
import com.rentradar.backend.web.dto.PredictionSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/predictions")
public class PredictionController {

    private final PredictionOrchestrator orchestrator;
    private final PredictionHistoryService history;

    public PredictionController(PredictionOrchestrator orchestrator,
                                PredictionHistoryService history) {

        this.orchestrator = orchestrator;
        this.history = history;
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
    public PredictionSummaryResponse byId(
            @PathVariable String id,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return history.one(id, principal.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * The estimate history, newest first.
     *
     * <p>{@code limit} exists so the Home screen can ask for the three it
     * displays rather than pulling the whole history to show three rows. The
     * History screen omits it and takes the default.
     */
    @GetMapping
    public List<PredictionSummaryResponse> mine(
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return history.forUser(principal.userId(), limit);
    }
}