package com.exchange.bff.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The CORS contract the browser depends on, pinned because breaking it is invisible here.
 *
 * <p>Every call from {@code web/} is cross-origin and carries the session as an httpOnly
 * cookie. If {@code allowCredentials} is ever dropped, nothing in this codebase fails --
 * the Java tests all set a Cookie header by hand and pass happily -- while every
 * authenticated request from a real browser answers NOT_AUTHENTICATED with no clue as to
 * why. This test is the only thing standing between that change and a day of debugging.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.cors.allowed-origin=http://localhost:3000",
                // Deliberately tiny: a preflight that spent budget would blow this on the
                // second call and the test below would catch it.
                "exchange.rate-limit.max-requests=3"})
class CorsIT {

    private static final String WEB_ORIGIN = "http://localhost:3000";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("a preflight from web/ allows credentials, or the session cookie is never sent")
    void preflightAllowsCredentials() {
        ResponseEntity<Void> response = preflight("/bff/auth/me", HttpMethod.GET);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(WEB_ORIGIN);
        // The header that makes the browser both send the cookie and accept the response.
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    /**
     * The case that actually broke: {@code /bff/auth/me} and {@code /bff/orders} sit behind
     * AuthenticationInterceptor, and a preflight carries no cookies, so the interceptor
     * rejected the preflight and the real request was never sent. The browser reports that
     * as a CORS error with no mention of authentication.
     */
    @Test
    @DisplayName("preflighting an authenticated endpoint succeeds even with no session")
    void preflightNeedsNoSession() {
        for (String path : List.of("/bff/auth/me", "/bff/orders", "/bff/wallet/balances")) {
            ResponseEntity<Void> response = preflight(path, HttpMethod.GET);
            assertThat(response.getStatusCode())
                    .as("preflight %s", path)
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getAccessControlAllowCredentials())
                    .as("credentials on %s", path)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the allowed origin is echoed exactly, never as a wildcard")
    void originIsNotAWildcard() {
        // A wildcard would let any site on the internet place orders with a logged-in
        // user's session -- and the CORS spec refuses it alongside credentials anyway, so
        // this would not even be a working mistake.
        assertThat(preflight("/bff/orders", HttpMethod.POST)
                .getHeaders().getAccessControlAllowOrigin())
                .isEqualTo(WEB_ORIGIN)
                .isNotEqualTo("*");
    }

    /** A preflight is the browser's overhead, not the user's traffic. Counting it would
     *  make the effective limit half of what is configured, and only for the requests that
     *  happen to be preflighted -- a limit nobody could reason about. */
    @Test
    @DisplayName("preflights do not spend the rate-limit budget")
    void preflightsAreNotRateLimited() {
        // Far more than the limit of 3 configured above.
        for (int i = 0; i < 10; i++) {
            assertThat(preflight("/bff/symbols", HttpMethod.GET).getStatusCode())
                    .as("preflight %s", i)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("another website's origin is refused")
    void foreignOriginIsRefused() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example.com");
        headers.setAccessControlRequestMethod(HttpMethod.POST);

        ResponseEntity<Void> response = rest.exchange(
                "http://localhost:" + port + "/bff/orders", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    @DisplayName("PATCH is allowed -- amending an order uses it, and a browser preflights it")
    void patchIsAllowed() {
        assertThat(preflight("/bff/orders/" + java.util.UUID.randomUUID(), HttpMethod.PATCH).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("DELETE is allowed too -- logout and cancel both use it")
    void deleteIsAllowed() {
        assertThat(preflight("/bff/auth/session", HttpMethod.DELETE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Void> preflight(String path, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(WEB_ORIGIN);
        headers.setAccessControlRequestMethod(method);
        headers.setAccessControlRequestHeaders(List.of("content-type"));
        return rest.exchange("http://localhost:" + port + path, HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);
    }
}
