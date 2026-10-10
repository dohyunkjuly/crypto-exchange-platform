package com.exchange.common.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Base32Test {

    private static final byte[] SECRET =
            "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    @DisplayName("round-trips, and tolerates how a human retypes it")
    void roundTrips() {
        String encoded = Base32.encode(SECRET);
        assertArrayEquals(SECRET, Base32.decode(encoded));

        // Off a printed sheet: grouped, lower case, hyphenated, stray padding.
        String retyped = encoded.toLowerCase().replaceAll("(.{4})", "$1-") + "==";
        assertArrayEquals(SECRET, Base32.decode(retyped));
    }

    @Test
    @DisplayName("encodes to the RFC 4648 alphabet only")
    void usesTheRfcAlphabet() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Base32.encode(SECRET));
    }

    @Test
    @DisplayName("a partial trailing group survives the round trip")
    void handlesPartialGroups() {
        for (int length = 1; length <= 16; length++) {
            byte[] data = new byte[length];
            for (int i = 0; i < length; i++) {
                data[i] = (byte) (i * 7 + 1);
            }
            assertArrayEquals(data, Base32.decode(Base32.encode(data)),
                    "failed at length " + length);
        }
    }

    @Test
    @DisplayName("rejects out-of-alphabet characters rather than decoding to garbage")
    void rejectsNonBase32() {
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("ABC!"));
        // 0, 1 and 8 are excluded precisely because they misread as O, I and B, so they
        // must fail loudly rather than silently decode to something else.
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("ABCD0189"));
    }
}
