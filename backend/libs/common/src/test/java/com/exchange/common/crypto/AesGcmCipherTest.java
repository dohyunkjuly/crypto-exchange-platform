package com.exchange.common.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void roundTripsThePlaintext() {
        AesGcmCipher cipher = new AesGcmCipher(randomKey());
        byte[] plaintext = "correct horse battery staple".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(plaintext, cipher.decrypt(cipher.encrypt(plaintext)));
    }

    @Test
    void rejectsAKeyOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new AesGcmCipher(new byte[16]));
    }

    @Test
    void refusesToDecryptUnderTheWrongKey() {
        byte[] ciphertext = new AesGcmCipher(randomKey())
                .encrypt("secret".getBytes(StandardCharsets.UTF_8));

        // GCM's authentication tag fails closed rather than returning garbage plaintext.
        assertThrows(IllegalStateException.class,
                () -> new AesGcmCipher(randomKey()).decrypt(ciphertext));
    }

    @Test
    void refusesToDecryptTamperedCiphertext() {
        AesGcmCipher cipher = new AesGcmCipher(randomKey());
        byte[] ciphertext = cipher.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        ciphertext[ciphertext.length - 1] ^= 0x01;

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(ciphertext));
    }
}
