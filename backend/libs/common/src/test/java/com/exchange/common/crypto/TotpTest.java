package com.exchange.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Checked against RFC 6238's own published test vectors rather than against this
 * implementation's output -- a self-consistent TOTP is worthless if it disagrees with
 * Google Authenticator, and reproducing the numbers printed in the spec is the only way
 * to know it does not.
 */
class TotpTest {

    /** RFC 6238 Appendix B uses the ASCII seed "12345678901234567890" for SHA-1. */
    private static final byte[] RFC_SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00Z");

    /**
     * The spec prints 8-digit codes; we emit 6, which is the low-order slice of the same
     * value -- so these are the RFC's digits with the leading two dropped.
     */
    @ParameterizedTest(name = "t={0}s -> {1}")
    @CsvSource({
            "59,          287082",
            "1111111109,  081804",
            "1111111111,  050471",
            "1234567890,  005924",
            "2000000000,  279037",
            "20000000000, 353130",
    })
    @DisplayName("reproduces RFC 6238's published SHA-1 vectors")
    void matchesRfcVectors(long epochSecond, String expected) {
        assertEquals(expected, Totp.codeAt(RFC_SECRET, epochSecond / 30));
    }

    @Test
    @DisplayName("accepts the current code and reports which step it matched")
    void acceptsCurrentCode() {
        String secret = Totp.generateSecret();
        long step = Totp.stepAt(NOW);
        String code = Totp.codeAt(Base32.decode(secret), step);

        assertEquals(OptionalLong.of(step), Totp.verify(secret, code, NOW, 1));
    }

    @Test
    @DisplayName("accepts one step either side, for clock skew")
    void acceptsAdjacentSteps() {
        String secret = Totp.generateSecret();
        byte[] raw = Base32.decode(secret);
        long step = Totp.stepAt(NOW);

        assertEquals(OptionalLong.of(step - 1),
                Totp.verify(secret, Totp.codeAt(raw, step - 1), NOW, 1));
        assertEquals(OptionalLong.of(step + 1),
                Totp.verify(secret, Totp.codeAt(raw, step + 1), NOW, 1));
    }

    /** The drift window is also the replay window: every extra step is another 30 seconds
     *  a stolen code stays live, so it has to stop somewhere. */
    @Test
    @DisplayName("rejects a code from outside the drift window")
    void rejectsCodeBeyondDrift() {
        String secret = Totp.generateSecret();
        String stale = Totp.codeAt(Base32.decode(secret), Totp.stepAt(NOW) - 2);

        assertEquals(OptionalLong.empty(), Totp.verify(secret, stale, NOW, 1));
    }

    @Test
    @DisplayName("rejects a wrong code, and anything that is not six digits")
    void rejectsMalformedInput() {
        String secret = Totp.generateSecret();

        assertEquals(OptionalLong.empty(), Totp.verify(secret, "000000", NOW, 1));
        assertEquals(OptionalLong.empty(), Totp.verify(secret, "12345", NOW, 1));
        assertEquals(OptionalLong.empty(), Totp.verify(secret, "1234567", NOW, 1));
        assertEquals(OptionalLong.empty(), Totp.verify(secret, "abcdef", NOW, 1));
        assertEquals(OptionalLong.empty(), Totp.verify(secret, "", NOW, 1));
        assertEquals(OptionalLong.empty(), Totp.verify(secret, null, NOW, 1));
    }

    @Test
    @DisplayName("a code is always six digits, including when the value is small")
    void codeIsAlwaysSixDigits() {
        byte[] raw = Base32.decode(Totp.generateSecret());
        for (long step = 0; step < 2_000; step++) {
            String code = Totp.codeAt(raw, step);
            assertEquals(6, code.length(), "step " + step + " produced " + code);
            assertTrue(code.chars().allMatch(Character::isDigit), code);
        }
    }

    @Test
    @DisplayName("secrets are 160 bits and do not repeat")
    void secretsAreStrongAndUnique() {
        String first = Totp.generateSecret();
        assertEquals(20, Base32.decode(first).length);
        assertNotEquals(first, Totp.generateSecret());
    }



    @Test
    @DisplayName("the provisioning URI carries what an authenticator app needs")
    void provisioningUriIsScannable() {
        String uri = Totp.provisioningUri("Exchange", "trader@example.com", "JBSWY3DPEHPK3PXP");

        assertTrue(uri.startsWith("otpauth://totp/Exchange:trader%40example.com?"), uri);

        // A space is %20, not +: authenticator apps do not decode form encoding, and would show
        // the + on the phone.
        String spaced = Totp.provisioningUri("Example Exchange Admin", "ops@example.com", "JBSWY3DPEHPK3PXP");
        assertTrue(spaced.startsWith("otpauth://totp/Example%20Exchange%20Admin:ops%40example.com?"), spaced);
        assertTrue(spaced.contains("&issuer=Example%20Exchange%20Admin"), spaced);
        assertTrue(uri.contains("secret=JBSWY3DPEHPK3PXP"), uri);
        // Both spellings of the issuer -- older apps read only one of them.
        assertTrue(uri.contains("issuer=Exchange"), uri);
        assertTrue(uri.contains("algorithm=SHA1"), uri);
        assertTrue(uri.contains("digits=6"), uri);
        assertTrue(uri.contains("period=30"), uri);
    }
}
