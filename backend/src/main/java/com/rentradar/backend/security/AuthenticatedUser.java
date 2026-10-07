package com.rentradar.backend.security;

import com.rentradar.backend.domain.type.UserRole;

/**
 * The authenticated caller, as established from a verified token.
 *
 * Carries the user id so ownership checks can scope queries without a second
 * database read, and the market so a request can never be served against a
 * market the caller does not belong to.
 */
public record AuthenticatedUser(String userId, UserRole role, String marketId) {
}