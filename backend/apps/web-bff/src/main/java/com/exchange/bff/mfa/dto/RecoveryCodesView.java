package com.exchange.bff.mfa.dto;

import java.util.List;

/**
 * The one and only time these exist in plaintext.
 *
 * <p>They are stored as SHA-256 hashes, so nothing -- not support, not the database -- can
 * produce them again. A client must show them and tell the user to keep them, because
 * losing both these and the authenticator means an account nobody can reopen.
 */
public record RecoveryCodesView(List<String> recoveryCodes) {
}
