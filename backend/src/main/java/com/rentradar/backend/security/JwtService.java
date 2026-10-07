package com.rentradar.backend.security;

import com.rentradar.backend.config.JwtProperties;
import com.rentradar.backend.domain.UserAccount;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies access tokens.
 *
 * Access tokens are deliberately short lived and carry only what authorisation
 * needs: subject, role and market. Nothing is placed in a token that must be
 * trusted to be current, because a token cannot be updated once issued and
 * cannot be revoked before it expires. Anything revocable lives in the refresh
 * token, which is stored and can be invalidated.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_MARKET = "market";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    private final SecretKey signingKey;
    private final JwtProperties properties;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(
                properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** Mints a short lived access token for an authenticated account. */
    public IssuedToken issueAccessToken(UserAccount account) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(properties.accessTokenMinutes()));

        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(account.id())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_ROLE, account.role().name())
                .claim(CLAIM_MARKET, account.marketId())
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();

        return new IssuedToken(token, expiry);
    }

    /**
     * Verifies a token and returns its claims, or empty if it is unusable for
     * any reason. Callers get no detail about why: distinguishing "expired" from
     * "forged" in a response is information an attacker can use.
     */
    public Optional<VerifiedToken> verify(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            // A refresh token presented as an access token must be rejected.
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                log.debug("Rejected token of unexpected type");
                return Optional.empty();
            }

            String subject = claims.getSubject();
            String role = claims.get(CLAIM_ROLE, String.class);
            String market = claims.get(CLAIM_MARKET, String.class);

            if (subject == null || role == null) {
                return Optional.empty();
            }

            return Optional.of(new VerifiedToken(
                    subject, role, market, claims.getExpiration().toInstant()));

        } catch (ExpiredJwtException e) {
            log.debug("Rejected expired token");
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            // Covers a bad signature, malformed token, wrong issuer and unsupported algorithm.
            log.debug("Rejected invalid token: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    public record VerifiedToken(String userId, String role, String marketId, Instant expiresAt) {}
}