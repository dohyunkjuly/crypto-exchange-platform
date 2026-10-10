package com.exchange.spot.openapi.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signing scheme, in one place so REST and the socket cannot drift: lowercase hex of
 * {@code HMAC-SHA256(secret, timestamp + method + pathWithQuery + body)}.
 *
 * <p>The body is in the string on purpose (docs/reference/api-signing-and-limits.md): orders are JSON
 * bodies here, and a signature over the query alone would let anyone who captured one request
 * replay it with a different body. The socket signs {@code timestamp + "GET" + "/v1/ws"} with an
 * empty body, the same function.
 */
public final class HmacSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private HmacSignature() {
    }

    public static String canonical(String timestamp, String method, String pathWithQuery, byte[] body) {
        return timestamp + method.toUpperCase() + pathWithQuery + new String(body, StandardCharsets.UTF_8);
    }

    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time: a byte-by-byte compare that stops at the first mismatch leaks, through its
     *  timing, how much of a guessed signature was right. */
    public static boolean verify(String secret, String canonical, String presentedHex) {
        if (presentedHex == null) {
            return false;
        }
        byte[] expected = sign(secret, canonical).getBytes(StandardCharsets.US_ASCII);
        byte[] presented = presentedHex.toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, presented);
    }
}
