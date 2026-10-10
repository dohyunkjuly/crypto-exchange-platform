package com.exchange.bff.auth;

import com.exchange.common.crypto.Sha256;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The value that goes in the session cookie, and the hash of it that goes in
 * {@code user_sessions.token_hash}. Generated together, once, so the raw value handed to the
 * browser is never written anywhere -- only its SHA-256 is.
 */
public final class SessionToken {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String raw;
    private final byte[] hash;

    private SessionToken(String raw, byte[] hash) {
        this.raw = raw;
        this.hash = hash;
    }

    public static SessionToken generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new SessionToken(raw, hash(raw));
    }

    /** Same hash a stored token must be compared against -- used to look up a presented cookie. */
    public static byte[] hash(String rawToken) {
        return Sha256.of(rawToken);
    }

    public String raw() {
        return raw;
    }

    public byte[] hash() {
        return hash;
    }
}
