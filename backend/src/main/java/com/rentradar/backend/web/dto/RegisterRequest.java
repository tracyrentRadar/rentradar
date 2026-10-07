package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.type.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(

        @NotBlank @Email @Size(max = 254)
        String email,

        /**
         * Length is the only rule. Composition rules push people toward
         * predictable substitutions and NIST dropped them in SP 800-63B.
         */
        @NotBlank @Size(min = 12, max = 128)
        String password,

        @NotNull
        UserRole role,

        /** Must match an existing RentalMarket. Never defaulted. */
        @NotBlank
        String marketId,

        /** BCP 47 tag. Optional, falls back to the market's default. */
        String locale
) {}