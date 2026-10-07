package com.rentradar.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ForecastScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForecastScheduler.class);

    private final ForecastService forecasts;

    public ForecastScheduler(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    /** 02:17 daily. Off the hour on purpose, since everything runs at :00. */
    @Scheduled(cron = "0 17 2 * * *")
    public void nightly() {
        log.info("Nightly forecast refresh starting");
        forecasts.regenerateAll();
    }

    /**
     * Dev convenience so a forecast exists without waiting until 02:17.
     *
     * Order 20 so it runs after StubModelSeeder at order 10. Without that, this
     * can run before any TREND model is registered and every location fails.
     */
    @Component
    @Order(20)
    @ConditionalOnProperty(name = "rentradar.forecast.regenerate-on-startup",
            havingValue = "true")
    static class StartupRefresh implements ApplicationRunner {

        private final ForecastService forecasts;

        StartupRefresh(ForecastService forecasts) {
            this.forecasts = forecasts;
        }

        @Override
        public void run(ApplicationArguments args) {
            forecasts.regenerateAll();
        }
    }
}