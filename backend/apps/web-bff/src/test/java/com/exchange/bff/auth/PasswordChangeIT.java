package com.exchange.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

/** Changing the password from inside the account, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class PasswordChangeIT {

    private static final String OLD_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-completely-different-one";

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
    @DisplayName("without 2FA the current password is the proof, and a wrong one changes nothing")
    void currentPasswordIsTheProofWithout2fa() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));

        ResponseEntity<Map> wrong = change(session, NEW_PASSWORD, "not-the-password-at-all", null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(wrong.getBody().get("code")).isEqualTo(2024);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(change(session, NEW_PASSWORD, OLD_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("the session that changed it stays; every other one ends")
    void keepsThisSessionEndsOthers() {
        String email = register();
        String here = sessionCookie(login(email, OLD_PASSWORD));
        String elsewhere = sessionCookie(login(email, OLD_PASSWORD));

        change(here, NEW_PASSWORD, OLD_PASSWORD, null);

        assertThat(get("/bff/auth/me", here).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/bff/auth/me", elsewhere).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The current password must not stand in for the factor: it is exactly what a phished
     *  account's attacker already has. */
    @Test
    @DisplayName("with 2FA on, only the authenticator code will do")
    void authenticatorCodeIsRequiredWith2fa() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));
        String secret = enrolTotp(session);

        assertThat(change(session, NEW_PASSWORD, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(change(session, NEW_PASSWORD, OLD_PASSWORD, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(change(session, NEW_PASSWORD, null, Totp.codeAt(secret, Instant.now().plusSeconds(30)))
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("an account with no password and no 2FA has nothing to prove with, and is told how to set one")
    void noPasswordAndNo2faIsRefused() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));
        jdbc.update("UPDATE user_users SET password_hash = NULL WHERE email = ?", email);

        ResponseEntity<Map> refused = change(session, NEW_PASSWORD, "anything-at-all-here", null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo(2025);
    }

    @Test
    @DisplayName("guessing the current password is capped like guessing it at login")
    void guessingIsCapped() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));
        for (int attempt = 0; attempt < 5; attempt++) {
            change(session, NEW_PASSWORD, "wrong-guess-number-" + attempt, null);
        }

        ResponseEntity<Map> capped = change(session, NEW_PASSWORD, OLD_PASSWORD, null);
        assertThat(capped.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a new password shorter than registration allows is refused")
    void shortPasswordIsRefused() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));
        assertThat(change(session, "short", OLD_PASSWORD, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("2FA status says when it was added, and nothing before it was")
    void mfaStatusCarriesItsDate() {
        String email = register();
        String session = sessionCookie(login(email, OLD_PASSWORD));
        assertThat(data(get("/bff/mfa", session)).get("enabledAt")).isNull();

        enrolTotp(session);
        assertThat(data(get("/bff/mfa", session)).get("enabledAt")).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private String register() {
        String email = "pwchange-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> response = post("/bff/auth/register",
                Map.of("email", email, "password", OLD_PASSWORD, "name", "IT"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdUserIds.add(((Number) data(response).get("id")).longValue());
        return email;
    }

    private ResponseEntity<Map> change(String session, String newPassword, String currentPassword,
                                       String mfaCode) {
        Map<String, String> payload = new HashMap<>(Map.of("newPassword", newPassword));
        if (currentPassword != null) {
            payload.put("currentPassword", currentPassword);
        }
        if (mfaCode != null) {
            payload.put("mfaCode", mfaCode);
        }
        return post("/bff/auth/password", payload, session);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return post("/bff/auth/login", Map.of("email", email, "password", password), null);
    }

    private String enrolTotp(String session) {
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        post("/bff/mfa/totp/confirm", Map.of("code", Totp.codeAt(secret, Instant.now())), session);
        return secret;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody().get("data");
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, String session) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(payload, headers(session)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String session) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers(session)), Map.class);
    }

    private HttpHeaders headers(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session);
        }
        return headers;
    }

    private String sessionCookie(ResponseEntity<Map> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        return cookies.get(0).split(";", 2)[0];
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
