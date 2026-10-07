package com.rentradar.backend.security;

/**
 * Authentication failed.
 *
 * Deliberately carries one message for every cause. Distinguishing "no such
 * account" from "wrong password" lets an attacker enumerate valid emails, and
 * distinguishing "locked" from "wrong password" confirms an account exists.
 */
public class AuthenticationException extends RuntimeException {

    public AuthenticationException(String message) {
        super(message);
    }
}