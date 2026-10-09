package com.rentradar.backend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token configuration.
 *
 * <p>The secret is validated for length at startup: HS512 signs with a 512 bit
 * key, and a short secret produces a weak signature rather than an error, so
 * the check has to be explicit. {@code JwtService} reads the secret as raw
 * UTF-8 bytes, so 64 characters is 64 bytes, which is that minimum.
 *
 * <p>The secret is supplied per environment, in a file git does not track, and
 * is never committed. An earlier revision of this file carried a real key
 * inside the {@code @Size} message attribute, which published the signing
 * secret to anyone able to read the repository. The message below is a message.
 */
@Validated
@ConfigurationProperties(prefix = "rentradar.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 64, message = "rentradar.jwt.secret must be at least 64 characters, "
                + "because HS512 signs with a 512 bit key and a shorter secret "
                + "weakens every token issued without ever failing")
        String secret,

        @NotBlank String issuer,

        @Min(1) int accessTokenMinutes,

        @Min(1) int refreshTokenDays
) {
}