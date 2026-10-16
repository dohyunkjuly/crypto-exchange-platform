package com.exchange.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The mobile app's session: the same token as the browser's cookie, delivered as a header
 * and presented as a bearer. The assertion that matters most is the negative one -- a
 * browser login never gets the token back in a form its scripts could read.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class BearerSessionIT {

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("redis", 6379);
        requirePort("kafka", 9092);
        requirePort("mongodb", 27018);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeSeededData() {
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("an app login gets the token as a header, no cookie, and the bearer works")
    void appLoginGetsABearer() {
        ResponseEntity<Map> response = login(register(), true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        String token = token(response);
        assertThat(token).isNotBlank();
        assertThat(get("/bff/auth/me", bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a browser login never sees the token outside the httpOnly cookie")
    void browserLoginNeverExposesTheToken() {
        ResponseEntity<Map> response = login(register(), false);

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotNull();
        assertThat(token(response)).isNull();
    }

    @Test
    @DisplayName("logging out with the bearer revokes it")
    void logoutRevokesTheBearer() {
        String token = token(login(register(), true));

        assertThat(rest.exchange(url("/bff/auth/session"), HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/bff/auth/me", bearer(token)).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Judged by the header alone: a forged bearer must not ride on a cookie sent beside it. */
    @Test
    @DisplayName("a bad bearer is refused even beside a valid cookie")
    void badBearerIsNotRescuedByACookie() {
        String cookie = login(register(), false).getHeaders().get(HttpHeaders.SET_COOKIE).get(0)
                .split(";", 2)[0];
        HttpHeaders headers = bearer("not-a-real-token");
        headers.add(HttpHeaders.COOKIE, cookie);

        assertThat(get("/bff/auth/me", headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The one endpoint that needs the token itself, not just the user: it keeps this session. */
    @Test
    @DisplayName("changing the password keeps the bearer session that changed it")
    void passwordChangeKeepsTheBearer() {
        String token = token(login(register(), true));

        assertThat(post("/bff/auth/password", Map.of("newPassword", "a-completely-different-one",
                "currentPassword", PASSWORD), bearer(token)).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(get("/bff/auth/me", bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("with 2FA the challenge carries no token; the code delivers it")
    void secondFactorDeliversTheBearer() {
        String email = register();
        String token = token(login(email, true));
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), bearer(token))).get("secret");
        assertThat(post("/bff/mfa/totp/confirm", Map.of("code", Totp.codeAt(secret, Instant.now())),
                bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> challenged = login(email, true);
        assertThat(token(challenged)).isNull();

        ResponseEntity<Map> completed = post("/bff/auth/login/mfa",
                Map.of("challengeToken", data(challenged).get("challengeToken"),
                        "code", Totp.codeAt(secret, Instant.now().plusSeconds(30))),
                appHeaders());
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(get("/bff/auth/me", bearer(token(completed))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ helpers

    private String register() {
        String email = "bearer-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), json());
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdUserIds.add(((Number) data(registered).get("id")).longValue());
        return email;
    }

    private ResponseEntity<Map> login(String email, boolean asApp) {
        return post("/bff/auth/login", Map.of("email", email, "password", PASSWORD),
                asApp ? appHeaders() : json());
    }

    private static String token(ResponseEntity<Map> response) {
        return response.getHeaders().getFirst(SessionCredentials.TOKEN_HEADER);
    }

    private static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders appHeaders() {
        HttpHeaders headers = json();
        headers.add(SessionCredentials.DELIVERY_HEADER, "bearer");
        return headers;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = json();
        headers.setBearerAuth(token);
        return headers;
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(payload, headers), Map.class);
    }

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
