package com.rentradar.backend.ml;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where the inference tier lives and how long we wait for it.
 *
 * <p>No default host. A missing base URL fails at startup rather than silently
 * defaulting to localhost and appearing to work on a developer machine while
 * being wrong everywhere else.
 */
@ConfigurationProperties(prefix = "rentradar.ml")
public record MlClientProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,

        /**
         * Register in-process stub artefacts at startup so the prediction path
         * resolves to something before any model is trained. Defaults to false:
         * a stub surviving into production unnoticed is the worst outcome here,
         * so creating one has to be deliberate.
         */
        boolean seedStubs
) {
    public MlClientProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "rentradar.ml.base-url must be set. The inference tier has no default "
                            + "location; guessing one hides a misconfiguration until production.");
        }
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
    }
}