package com.exchange.bff.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;

/** Builds the Set-Cookie header for the opaque session token. Secure works fine over plain
 *  http://localhost too -- browsers treat localhost as a trustworthy origin -- so there is
 *  no separate dev/prod toggle. Public: {@code COOKIE_NAME} is how any controller in this
 *  app binds the cookie with {@code @CookieValue}, not just AuthController. */
public final class SessionCookies {

    public static final String COOKIE_NAME = "session";

    private SessionCookies() {
    }

    public static ResponseCookie set(String rawToken, Instant expiresAt) {
        return ResponseCookie.from(COOKIE_NAME, rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.between(Instant.now(), expiresAt))
                .build();
    }

    public static ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build();
    }
}
