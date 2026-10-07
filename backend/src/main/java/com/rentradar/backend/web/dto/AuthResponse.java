package com.rentradar.backend.web.dto;

import com.rentradar.backend.security.AuthenticationService.TokenPair;

import java.time.Instant;

public record AuthResponse(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        String tokenType
) {
    public static AuthResponse from(TokenPair pair) {
        return new AuthResponse(
                pair.accessToken(),
                pair.accessTokenExpiresAt(),
                pair.refreshToken(),
                pair.refreshTokenExpiresAt(),
                "Bearer");
    }
}