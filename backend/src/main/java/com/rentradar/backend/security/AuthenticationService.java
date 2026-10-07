package com.rentradar.backend.security;

import com.rentradar.backend.config.JwtProperties;
import com.rentradar.backend.domain.RefreshToken;
import com.rentradar.backend.domain.UserAccount;
import com.rentradar.backend.domain.type.UserRole;
import com.rentradar.backend.repository.RefreshTokenRepository;
import com.rentradar.backend.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Registration, login, refresh rotation and logout.
 *
 * <p>Two things here are deliberate and easy to undo by accident. Every failure
 * path throws the same message, so the endpoint never tells an attacker whether
 * an email exists. And a refresh token that is presented after it has already
 * been exchanged revokes the whole chain, because the only way that happens is
 * that someone holds a copy they should not.
 */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    /** 256 bits. Refresh tokens are random, so entropy is the whole defence. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    private static final String CREDENTIALS_REJECTED = "Invalid email or password";
    private static final String REFRESH_REJECTED = "Invalid or expired refresh token";

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * A throwaway BCrypt hash, verified against when no account matches, so an
     * unknown email costs the same wall clock time as a wrong password. Computed
     * once at startup rather than pasted in as a literal.
     */
    private final String dummyHash;

    public AuthenticationService(
            UserAccountRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            JwtProperties jwtProperties) {

        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public TokenPair register(
            String email,
            String rawPassword,
            UserRole role,
            String marketId,
            String locale) {

        Instant now = Instant.now();
        String normalised = normalise(email);

        // Self service registration cannot mint an administrator.
        if (role == UserRole.ADMIN) {
            throw new IllegalArgumentException("That role cannot be self assigned");
        }

        try {
            UserAccount account = users.save(UserAccount.register(
                    normalised,
                    passwordEncoder.encode(rawPassword),
                    role,
                    marketId,
                    locale,
                    now));

            return issueTokenPair(account, now);

        } catch (DuplicateKeyException e) {
            // The unique index is the authority, not a prior read. A check then
            // insert loses the race under concurrent signups for the same email.
            throw new EmailAlreadyRegisteredException("An account with that email already exists");
        }
    }

    public TokenPair login(String email, String rawPassword) {
        Instant now = Instant.now();
        Optional<UserAccount> found = users.findByEmail(normalise(email));

        if (found.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw new AuthenticationException(CREDENTIALS_REJECTED);
        }

        UserAccount account = found.get();

        if (account.isLockedAt(now)) {
            throw new AuthenticationException(
                    "Account temporarily locked. Try again later.");
        }

        if (!passwordEncoder.matches(rawPassword, account.passwordHash())) {
            recordFailure(account, now);
            throw new AuthenticationException(CREDENTIALS_REJECTED);
        }

        UserAccount updated = users.save(account.withSuccessfulLogin(now));
        return issueTokenPair(updated, now);
    }

    /**
     * Exchanges a refresh token for a new pair and retires the old one. The old
     * token is kept rather than deleted, pointing at its replacement, so that a
     * later presentation of it is recognisable as theft.
     */
    public TokenPair refresh(String rawRefreshToken) {
        Instant now = Instant.now();

        RefreshToken presented = refreshTokens
                .findByTokenHash(TokenHasher.hash(rawRefreshToken))
                .orElseThrow(() -> new AuthenticationException(REFRESH_REJECTED));

        if (presented.isRotated()) {
            revokeAllSessions(presented.userId());
            log.warn("Refresh token reuse detected for user {}. All sessions revoked.",
                    presented.userId());
            throw new AuthenticationException(REFRESH_REJECTED);
        }

        if (!presented.isUsableAt(now)) {
            throw new AuthenticationException(REFRESH_REJECTED);
        }

        UserAccount account = users.findById(presented.userId())
                .orElseThrow(() -> new AuthenticationException(REFRESH_REJECTED));

        if (account.isLockedAt(now)) {
            throw new AuthenticationException(
                    "Account temporarily locked. Try again later.");
        }

        TokenPair pair = issueTokenPair(account, now);
        refreshTokens.save(revoke(presented, pair.refreshTokenId()));
        return pair;
    }

    /** Idempotent, and silent on an unknown token so it reveals nothing. */
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(TokenHasher.hash(rawRefreshToken))
                .filter(token -> !token.revoked())
                .map(token -> revoke(token, token.replacedByTokenId()))
                .ifPresent(refreshTokens::save);
    }

    private TokenPair issueTokenPair(UserAccount account, Instant now) {
        var access = jwtService.issueAccessToken(account);

        String rawRefresh = generateRefreshToken();
        Instant refreshExpiry = now.plus(Duration.ofDays(jwtProperties.refreshTokenDays()));

        // Only the hash is stored. A database disclosure yields nothing usable.
        RefreshToken saved = refreshTokens.save(new RefreshToken(
                null,
                TokenHasher.hash(rawRefresh),
                account.id(),
                now,
                refreshExpiry,
                false,
                null));

        return new TokenPair(
                access.token(), access.expiresAt(),
                rawRefresh, refreshExpiry, saved.id());
    }

    private void recordFailure(UserAccount account, Instant now) {
        int attempts = account.failedLoginCount() + 1;
        boolean shouldLock = attempts >= MAX_FAILED_ATTEMPTS;
        Instant lockUntil = shouldLock ? now.plus(LOCKOUT_DURATION) : account.lockedUntil();

        if (shouldLock) {
            log.warn("Account {} locked after {} failed attempts", account.id(), attempts);
        }

        users.save(account.withFailedLogin(attempts, lockUntil));
    }

    private void revokeAllSessions(String userId) {
        refreshTokens.findByUserIdAndRevokedFalse(userId).stream()
                .map(token -> revoke(token, token.replacedByTokenId()))
                .forEach(refreshTokens::save);
    }

    private static RefreshToken revoke(RefreshToken token, String replacedByTokenId) {
        return new RefreshToken(
                token.id(), token.tokenHash(), token.userId(),
                token.issuedAt(), token.expiresAt(), true, replacedByTokenId);
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The raw refresh token appears here and nowhere else after this. It is
     * returned to the client once and never readable from the database again.
     */
    public record TokenPair(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            String refreshTokenId
    ) {}
}