package com.rentradar.backend.domain;

import com.rentradar.backend.domain.type.UserRole;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * A registered account. Mongo assigns the id on first save, so {@code id} and
 * {@code version} are null until then.
 *
 * <p>The annotations must come from org.springframework.data.annotation, not
 * jakarta.persistence. The JPA ones compile cleanly and are silently ignored by
 * the Mongo mapper, which costs you both id mapping and optimistic locking with
 * no error to tell you.
 */
@Document(collection = "users")
public record UserAccount(

        @Id
        String id,

        @Version
        Long version,

        @Indexed(unique = true)
        String email,

        String passwordHash,

        @Indexed
        UserRole role,

        /** Scopes the account to a RentalMarket. Never inferred, never defaulted. */
        @Indexed
        String marketId,

        /** BCP 47 tag, used for server side formatting and notification copy. */
        String locale,

        Instant createdAt,

        Instant lastLoginAt,

        int failedLoginCount,

        Instant lockedUntil
) {

    public UserAccount {
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(marketId, "marketId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        // Normalise here rather than at the call sites, so the unique index on
        // email cannot be defeated by casing or stray whitespace.
        email = email.trim().toLowerCase(Locale.ROOT);

        if (email.isEmpty()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (failedLoginCount < 0) {
            throw new IllegalArgumentException("failedLoginCount must not be negative");
        }
    }

    /** A brand new account, before Mongo has assigned an id. */
    public static UserAccount register(
            String email,
            String passwordHash,
            UserRole role,
            String marketId,
            String locale,
            Instant now) {

        return new UserAccount(
                null, null, email, passwordHash, role, marketId, locale,
                now, null, 0, null);
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Copy with the failure counter advanced. Call sites that rebuild all eleven
     * components by hand are how two of them end up transposed.
     */
    public UserAccount withFailedLogin(int attempts, Instant lockUntil) {
        return new UserAccount(
                id, version, email, passwordHash, role, marketId, locale,
                createdAt, lastLoginAt, attempts, lockUntil);
    }

    /** Copy with the failure counter and any lock cleared. */
    public UserAccount withSuccessfulLogin(Instant now) {
        return new UserAccount(
                id, version, email, passwordHash, role, marketId, locale,
                createdAt, now, 0, null);
    }

    /** Deliberately omits passwordHash so it cannot reach a log line. */
    @Override
    public String toString() {
        return "UserAccount[id=%s, email=%s, role=%s, marketId=%s]"
                .formatted(id, email, role, marketId);
    }
}