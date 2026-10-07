package com.rentradar.backend.web.dto;

import com.rentradar.backend.domain.UserAccount;
import com.rentradar.backend.domain.type.UserRole;

import java.time.Instant;

/** Note what is absent: passwordHash, version, and the lockout counters. */
public record MeResponse(
        String id,
        String email,
        UserRole role,
        String marketId,
        String locale,
        Instant createdAt,
        Instant lastLoginAt
) {
    public static MeResponse from(UserAccount account) {
        return new MeResponse(
                account.id(), account.email(), account.role(),
                account.marketId(), account.locale(),
                account.createdAt(), account.lastLoginAt());
    }
}