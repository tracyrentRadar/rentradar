package com.rentradar.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates and hashes refresh tokens.
 *
 * Tokens are 256 bits from a cryptographically secure source, so they are not
 * guessable and a fast hash is appropriate. BCrypt would be wrong here: its cost
 * exists to slow down guessing of low entropy secrets, and applying it to random
 * 256 bit values would only make every refresh expensive.
 */
public final class TokenHasher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private TokenHasher() {
    }

    /** A new opaque token, URL safe, returned to the client once and never stored. */
    public static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The value actually persisted. */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform; absence is unrecoverable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}