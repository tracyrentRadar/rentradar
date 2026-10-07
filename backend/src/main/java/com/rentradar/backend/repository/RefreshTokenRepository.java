package com.rentradar.backend.repository;

import com.rentradar.backend.domain.RefreshToken;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {

    /** Lookup is always by hash. The raw token is never stored, so never queried. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Live sessions for a user. Filtering on revoked in the query rather than in
     * the stream keeps retired tokens off the wire, and they accumulate: every
     * refresh retires one.
     */
    List<RefreshToken> findByUserIdAndRevokedFalse(String userId);
}