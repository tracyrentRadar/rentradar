package com.rentradar.backend.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable record of a security-relevant action. There is no update path:
 * entries are appended and expire automatically after 365 days.
 *
 * The client address is stored only as a hash, so the log supports investigation
 * without retaining a directly identifying value.
 */
@SuppressWarnings("removal")
@Document(collection = "audit_log")
public record AuditLogEntry(

        @Id String id,

        @Indexed String actorId,

        String action,

        @Indexed String correlationId,

        String outcome,

        String ipHash,

        @Indexed(expireAfterSeconds = 31_536_000) Instant occurredAt
) {

    public AuditLogEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}