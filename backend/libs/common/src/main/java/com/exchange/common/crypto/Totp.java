package com.exchange.common.crypto;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 -- the six digits an authenticator app shows.
 *
 * <p>HMAC-SHA1, 6 digits, 30-second steps: interoperability, not a security choice. Every
 * authenticator implements exactly this and a stronger hash would fail to enrol; the
 * security comes from the secret's 160 bits and the sub-minute validity.
 *
 * <p>Framework-free and stateless, beside {@link AesGcmCipher} (docs/STYLE.md) --
 * the caller supplies both the secret and the current time.
 *
 * <p><b>{@link #verify} returns the matched time step, not a boolean</b>, which is what
 * makes replay protection possible: the caller persists the step it accepted and refuses
 * anything at or below it. Otherwise a code read off a screen stays valid for up to 90
 * seconds in an attacker's hand.
 */
public final class Totp {

    private static final String HMAC = "HmacSHA1";
    private static final int DIGITS = 6;
    private static final int MODULUS = 1_000_000;
    private static final Duration STEP = Duration.ofSeconds(30);

    /** 160 bits, the minimum RFC 4226 allows and what every authenticator expects. */
    private static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** A fresh enrolment secret, base32, ready to encrypt and store. */
    public static String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans as a QR code.
     *
     * <p>{@code issuer} appears twice on purpose, in the label path and as a parameter:
     * older apps read only one, and an entry shows up nameless if they disagree.
     */
    public static String provisioningUri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label
                + "?secret=" + base32Secret
                + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS
                + "&period=" + STEP.toSeconds();
    }

    /**
     * @param driftSteps steps either side to accept; 1 covers ordinary phone clock skew.
     *                   Every extra step widens the replay window, so keep it small.
     * @return the time step the code matched, for the caller to store against replay, or
     *         empty if no step in the window produced it
     */
    public static OptionalLong verify(String base32Secret, String code, Instant now,
                                      int driftSteps) {
        // Rejecting the shape first keeps the HMAC loop off attacker-controlled lengths.
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(Character::isDigit)) {
            return OptionalLong.empty();
        }
        byte[] secret = Base32.decode(base32Secret);
        long current = now.getEpochSecond() / STEP.toSeconds();
        for (long step = current - driftSteps; step <= current + driftSteps; step++) {
            if (constantTimeEquals(codeAt(secret, step), code)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * The code an authenticator would show for this secret at this instant.
     *
     * <p>Public because generation is symmetric -- anyone holding the secret can already do
     * this. For tests and tooling standing in for an authenticator; servers call
     * {@link #verify}, which handles drift and reports the step for replay protection.
     */
    public static String codeAt(String base32Secret, Instant at) {
        return codeAt(Base32.decode(base32Secret), stepAt(at));
    }

    /** The step {@code now} falls in -- callers compare this against a stored last-used
     *  step to reject a code that has already been spent. */
    public static long stepAt(Instant now) {
        return now.getEpochSecond() / STEP.toSeconds();
    }

    /** HOTP (RFC 4226) over the time step: HMAC the counter, then take the four bytes the
     *  last nibble points at. The dynamic offset is what stops the digits being a fixed
     *  slice of the hash. */
    static String codeAt(byte[] secret, long step) {
        byte[] counter = ByteBuffer.allocate(Long.BYTES).putLong(step).array();
        byte[] hash = hmac(secret, counter);

        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return String.format("%0" + DIGITS + "d", binary % MODULUS);
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA1 is required of every JVM; absent, nothing here can work.
            throw new IllegalStateException("HMAC-SHA1 unavailable", e);
        }
    }

    /** Compares in time independent of how many leading digits match, so the comparison
     *  itself leaks nothing about the correct code. */
    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Percent-encoded, never form-encoded: {@link URLEncoder} writes a space as {@code +},
     * which the key-URI format does not decode, so an issuer with a space in it would show on
     * the phone as {@code Example+Exchange}. Every operator's name has a space in it.
     */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
