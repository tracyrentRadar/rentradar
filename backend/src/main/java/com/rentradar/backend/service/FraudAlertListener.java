package com.rentradar.backend.service;

import com.rentradar.backend.domain.FraudRiskAlert;
import com.rentradar.backend.event.HighRiskDetected;
import com.rentradar.backend.repository.FraudRiskAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Writes the alert off the request path. A failure here must not fail the
 * caller's prediction, which already succeeded and is already stored.
 */
@Component
public class FraudAlertListener {

    private static final Logger log = LoggerFactory.getLogger(FraudAlertListener.class);

    private final FraudRiskAlertRepository alerts;

    public FraudAlertListener(FraudRiskAlertRepository alerts) {
        this.alerts = alerts;
    }

    @Async("eventExecutor")
    @EventListener
    public void on(HighRiskDetected event) {
        try {
            alerts.save(new FraudRiskAlert(
                    null,
                    event.propertyId(),
                    event.marketId(),
                    event.assessment().riskScore(),
                    event.assessment().riskLevel(),
                    event.assessment().reconstructionError(),
                    event.assessment().principalFactor(),
                    event.assessment().model().qualifiedVersion(),
                    event.occurredAt()));

            log.warn("HIGH risk alert written for property {} ({})",
                    event.propertyId(), event.assessment().principalFactor());

        } catch (RuntimeException e) {
            log.error("Could not write fraud alert for property {}: {}",
                    event.propertyId(), e.getMessage());
        }
    }
}