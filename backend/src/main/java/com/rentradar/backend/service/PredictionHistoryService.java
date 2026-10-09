package com.rentradar.backend.service;

import com.rentradar.backend.domain.MarketLocation;
import com.rentradar.backend.domain.PricePrediction;
import com.rentradar.backend.domain.PropertyListing;
import com.rentradar.backend.repository.MarketLocationRepository;
import com.rentradar.backend.repository.PricePredictionRepository;
import com.rentradar.backend.repository.PropertyListingRepository;
import com.rentradar.backend.web.dto.PredictionSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns stored estimates into rows a screen can draw.
 *
 * <p>The point of this class is the query count. A naive version reads the
 * predictions, then reads a property per row, then a location per property,
 * which is three hundred round trips for a hundred estimates. This reads the
 * predictions once, then collects the distinct property ids and reads them in
 * one call, then does the same for locations. Three queries, whatever the row
 * count.
 *
 * <p>That matters more here than it would elsewhere. The target user is on a
 * Ghanaian mobile connection, and the non-functional target is a p95 of 500 ms.
 */
@Service
public class PredictionHistoryService {

    /**
     * A hard ceiling independent of what the caller asks for. The history
     * endpoint has no paging yet, and an unbounded list is how a slow screen
     * becomes an out of memory crash once someone has used the app for a term.
     */
    private static final int MAX_ROWS = 200;

    private final PricePredictionRepository predictions;
    private final PropertyListingRepository properties;
    private final MarketLocationRepository locations;

    public PredictionHistoryService(PricePredictionRepository predictions,
                                    PropertyListingRepository properties,
                                    MarketLocationRepository locations) {
        this.predictions = predictions;
        this.properties = properties;
        this.locations = locations;
    }

    /** Newest first, which is the order both screens display. */
    public List<PredictionSummaryResponse> forUser(String userId, int limit) {
        List<PricePrediction> rows = predictions.findByUserIdOrderByCreatedAtDesc(userId);

        int cap = Math.min(Math.max(limit, 1), MAX_ROWS);
        if (rows.size() > cap) {
            rows = rows.subList(0, cap);
        }
        return describe(rows);
    }

    /**
     * Ownership is part of the lookup rather than a check afterwards, so a
     * prediction belonging to someone else is not found rather than found and
     * then refused.
     */
    public Optional<PredictionSummaryResponse> one(String predictionId, String userId) {
        return predictions.findByIdAndUserId(predictionId, userId)
                .map(prediction -> describe(List.of(prediction)).get(0));
    }

    private List<PredictionSummaryResponse> describe(List<PricePrediction> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Set<String> propertyIds = new LinkedHashSet<>();
        for (PricePrediction row : rows) {
            if (row.propertyId() != null) {
                propertyIds.add(row.propertyId());
            }
        }

        Map<String, PropertyListing> propertyById = new HashMap<>();
        for (PropertyListing property : properties.findAllById(propertyIds)) {
            propertyById.put(property.id(), property);
        }

        Set<String> locationIds = new LinkedHashSet<>();
        for (PropertyListing property : propertyById.values()) {
            if (property.locationId() != null) {
                locationIds.add(property.locationId());
            }
        }

        Map<String, MarketLocation> locationById = new HashMap<>();
        for (MarketLocation location : locations.findAllById(locationIds)) {
            locationById.put(location.id(), location);
        }

        List<PredictionSummaryResponse> out = new ArrayList<>(rows.size());
        for (PricePrediction row : rows) {
            PropertyListing property = propertyById.get(row.propertyId());
            MarketLocation location = property == null
                    ? null
                    : locationById.get(property.locationId());

            out.add(PredictionSummaryResponse.of(row, property, location));
        }
        return out;
    }
}