package com.rentradar.backend.config;

import com.rentradar.backend.domain.type.AreaUnit;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Import configuration. Only decisions about the platform live here: which
 * markets to serve, how wide to search, and which listing sources are accepted.
 * Everything geographic is discovered, never configured.
 */
@Validated
@ConfigurationProperties(prefix = "rentradar.reference-data")
public record ReferenceDataProperties(

        /** ISO 3166-1 alpha-2 country codes to import. */
        @NotEmpty List<String> markets,

        @Min(1000) int localityRadiusMetres,

        @Min(1) int maxCitiesPerMarket,

        @NotEmpty List<String> validSources,

        AreaUnit defaultAreaUnit
) {
    public ReferenceDataProperties {
        if (defaultAreaUnit == null) {
            defaultAreaUnit = AreaUnit.SQM;
        }
    }
}