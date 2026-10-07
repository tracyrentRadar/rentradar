package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.type.AreaUnit;
import com.rentradar.backend.service.NewPropertyCommand;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CreatePropertyRequest(

        @NotBlank String marketId,
        @NotBlank String city,
        @NotBlank String district,

        @NotBlank @Size(max = 140) String title,

        @Min(1) @Max(10) int bedrooms,
        @Min(1) @Max(10) int bathrooms,

        /** Optional. Null means the listing did not say, which the model uses. */
        @Min(0) @Max(20) Integer toilets,

        /** Optional, same reason. */
        @Min(0) @Max(20) Integer parkingSpaces,

        /** Optional. As published, e.g. "Apartment", "Detached Duplex". */
        @Size(max = 60) String propertyType,

        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal size,

        /** Required, never defaulted. Binding rule 3. */
        @NotNull AreaUnit sizeUnit,

        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal price,

        /** Required, never assumed from the market. Binding rule 2. */
        @NotBlank @Size(min = 3, max = 3) String currency,

        /**
         * Deliberately not @NotNull. Omitting it means the listing is silent on
         * furnishing, which is a different fact from stating it is unfurnished,
         * and three quarters of observed listings are silent.
         */
        Boolean furnished,

        /** Optional. Empty is meaningful: a genuine advert usually lists some. */
        @Size(max = 60) List<@NotBlank @Size(max = 60) String> amenities,

        @NotBlank String source,

        /** Optional. Defaults to now for a listing observed today. */
        Instant postedAt
) {
    public NewPropertyCommand toCommand() {
        return new NewPropertyCommand(
                marketId, city, district, title,
                bedrooms, bathrooms, toilets, parkingSpaces, propertyType,
                size, sizeUnit, price, currency,
                furnished, amenities, source, postedAt);
    }
}