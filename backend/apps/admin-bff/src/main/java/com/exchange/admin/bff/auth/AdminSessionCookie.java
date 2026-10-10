package com.exchange.admin.bff.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/** {@code admin_session}, never {@code session}: the two apps' cookies must not be confused
 *  by a browser that has both. Secure is fine over http://localhost, as for web-bff. */
public final class AdminSessionCookie {

    public static final String NAME = "admin_session";

    private AdminSessionCookie() {
    }

    public static ResponseCookie set(String rawToken, Instant expiresAt) {
        return ResponseCookie.from(NAME, rawToken).httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(Duration.between(Instant.now(), expiresAt)).build();
    }

    public static ResponseCookie clear() {
        return ResponseCookie.from(NAME, "").httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(0).build();
    }
}
