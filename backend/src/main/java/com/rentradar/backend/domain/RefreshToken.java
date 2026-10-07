package com.rentradar.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored refresh token, held as a hash.
 *
 * Only the hash is persisted: a database disclosure must not hand an attacker
 * usable tokens. The hash is SHA-256 rather than BCrypt because a refresh token
 * is 256 bits of randomness, not a guessable password. Deliberately slow hashing
 * defends low entropy secrets; here it would only make every refresh expensive
 * while adding nothing, since the input cannot be brute forced regardless.
 *
 * Rotation is recorded through replacedByTokenId, so a chain of rotations can be
 * followed. Presenting an already rotated token is a strong signal of theft and
 * the whole chain is revoked on detection.
 */
@Document(collection = "refresh_tokens")
public record RefreshToken(

        @Id String id,

        @Indexed(unique = true) String tokenHash,

        @Indexed String userId,

        Instant issuedAt,

        /** TTL index: expired tokens are reaped rather than accumulating. */
        @Indexed(expireAfterSeconds = 0) Instant expiresAt,

        boolean revoked,

        /** Set when this token has been rotated; identifies its successor. */
        String replacedByTokenId
) {

    public RefreshToken {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");

        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean isUsableAt(Instant now) {
        return !revoked && now.isBefore(expiresAt);
    }

    /** True once rotated away from, which on presentation indicates reuse. */
    public boolean isRotated() {
        return replacedByTokenId != null;
    }
}