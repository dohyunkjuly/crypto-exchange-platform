package com.exchange.admin.bff.auth;

import com.exchange.common.crypto.Sha256;
import java.security.SecureRandom;
import java.util.Base64;

/** The raw value goes to the browser once; only its SHA-256 is ever stored. As web-bff's. */
public final class AdminSessionToken {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String raw;
    private final byte[] hash;

    private AdminSessionToken(String raw) {
        this.raw = raw;
        this.hash = hash(raw);
    }

    public static AdminSessionToken generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new AdminSessionToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

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
