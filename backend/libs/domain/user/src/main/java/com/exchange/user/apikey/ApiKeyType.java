package com.exchange.user.apikey;

public enum ApiKeyType {
    /** Shared secret. We must store it encrypted, because verifying a request recomputes the signature. */
    HMAC_SHA256,
    /** The client keeps the private key; we hold only the public half. Preferred. */
    ED25519
}
