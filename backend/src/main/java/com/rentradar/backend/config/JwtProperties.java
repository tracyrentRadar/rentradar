package com.rentradar.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token configuration.
 *
 * The secret is validated for length at startup: HS512 requires a 512 bit key,
 * and a short secret is a weak signature rather than an error, so the check has
 * to be explicit. It is supplied per environment and never committed.
 */
@Validated
@ConfigurationProperties(prefix = "rentradar.jwt")
public record JwtProperties(

        @NotBlank @Size(min = 64, message = "Tmya4gWlSQbKGHnzIF6aolsihG2+kdPp4ylutZ+OsJ8Y3OkFJpY6YmK1V0P+ldqmiLxtgXBazqSaxKvMXfkEgQ==")
        String secret,

        @NotBlank String issuer,

        @Min(1) int accessTokenMinutes,

        @Min(1) int refreshTokenDays
) {
}