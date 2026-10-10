package com.exchange.common.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM, for a secret that must be recovered rather than compared -- an API key's
 * HMAC secret, a TOTP seed. Not for anything only ever compared: a password gets bcrypt, a
 * session token gets a plain SHA-256 hash.
 *
 * <p>Framework-free like the rest of this module -- the key is handed in by the caller,
 * however they read it (an env var, a KMS call); this class knows nothing about Spring or
 * where key material comes from.
 */
public final class AesGcmCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes, got " + key.length);
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** IV prepended to the ciphertext -- it isn't secret, and this is the only place it
     *  needs to travel alongside the data it was used with. */
    public byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @throws IllegalStateException the ciphertext was tampered with, truncated, or
     *          encrypted under a different key -- GCM's tag check fails closed */
    public byte[] decrypt(byte[] stored) {
        try {
            byte[] iv = Arrays.copyOfRange(stored, 0, GCM_IV_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(stored, GCM_IV_BYTES, stored.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption failed", e);
        }
    }
}
