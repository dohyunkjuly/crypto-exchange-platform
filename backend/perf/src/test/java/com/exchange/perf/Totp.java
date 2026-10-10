package com.exchange.perf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 over a base32 seed, as the dev accounts' authenticator would compute it. */
final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    static String now(String base32Secret) throws Exception {
        byte[] key = decode(base32Secret);
        byte[] counter = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() / 30_000).array();
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] h = mac.doFinal(counter);
        int o = h[h.length - 1] & 0xf;
        int code = ((h[o] & 0x7f) << 24 | (h[o + 1] & 0xff) << 16 | (h[o + 2] & 0xff) << 8 | (h[o + 3] & 0xff)) % 1_000_000;
        return String.format("%06d", code);
    }

    private static byte[] decode(String s) {
        s = s.replace("=", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buffer = 0, bits = 0;
        for (char c : s.toCharArray()) {
            buffer = (buffer << 5) | ALPHABET.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.put((byte) (buffer >> (bits - 8)));
                bits -= 8;
            }
        }
        byte[] bytes = new byte[out.position()];
        out.flip();
        out.get(bytes);
        return bytes;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
