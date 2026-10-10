package com.exchange.user.apikey.dto;

import com.exchange.user.apikey.UserApiKey;

/** What a successful create hands the caller: the saved key, and its plaintext secret --
 *  the only time that secret is ever available outside {@link
 *  com.exchange.common.crypto.AesGcmCipher}. */
public record CreatedApiKey(UserApiKey key, String secret) {
}
