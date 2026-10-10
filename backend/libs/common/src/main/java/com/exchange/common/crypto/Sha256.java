package com.exchange.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256, for credentials that are compared and never recovered.
 *
 * <p>Right for a value that already carries its own entropy -- a session token, a recovery
 * code -- where bcrypt's work factor would add latency per request without making a
 * 160-bit random string harder to guess. <b>A password must never come through here</b>;
 * see AGENTS.md's Credentials table.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static byte[] of(String value) {
        return of(value.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] of(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-mandatory algorithm", e);
        }
    }
}
