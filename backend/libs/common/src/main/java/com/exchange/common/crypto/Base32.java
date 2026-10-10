package com.exchange.common.crypto;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

/**
 * RFC 4648 base32, without padding.
 *
 * <p>Used wherever a secret has to survive a human: an authenticator secret typed in by
 * hand, a recovery code copied off a printed sheet. Its alphabet deliberately omits 0, 1
 * and 8 because they misread as O, I and B -- which is the entire reason it exists here
 * rather than base64, whose case-sensitivity and {@code +/} make it hostile to retyping.
 *
 * <p>No {@code =} padding: the only places these strings appear are an {@code otpauth://}
 * query parameter, where {@code =} would need escaping, and a code a person reads aloud.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                encoded.append(ALPHABET.charAt((buffer >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        // A trailing partial group is left-aligned and zero-filled.
        if (bits > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return encoded.toString();
    }

    /** Tolerates the ways a person retypes one of these: spaces, hyphens, lower case and
     *  stray padding all mean nothing here. An out-of-alphabet character does not, and
     *  fails loudly rather than decoding to something subtly wrong. */
    public static byte[] decode(String encoded) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : encoded.replace(" ", "").replace("-", "").replace("=", "")
                .toUpperCase(Locale.ROOT).toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("not base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                decoded.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return decoded.toByteArray();
    }
}
