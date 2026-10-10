package com.exchange.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.common.mail.Mailer;
import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Forgetting a password and getting back in, over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class PasswordResetIT {

    private static final String OLD_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "a-completely-different-one";

    @TestConfiguration
    static class CapturingMailerConfig {
        @Bean
        @Primary
        CapturingMailer capturingMailer() {
            return new CapturingMailer();
        }
    }

    static class CapturingMailer implements Mailer {
        private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();

        @Override
        public void send(String to, String subject, String body) {
            bodies.add(body);
        }

        String lastBody() {
            String last = null;
            for (String body : bodies) {
                last = body;
            }
            return last;
        }

        int count() {
            return bodies.size();
        }

        void clear() {
            bodies.clear();
        }
    }

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

    @Autowired
    private CapturingMailer mailer;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void clearMailbox() {
        mailer.clear();
    }

    @AfterEach
    void removeSeededData() {
        jdbc.update("DELETE FROM user_password_reset_codes WHERE user_id IN "
                + "(SELECT id FROM user_users WHERE email LIKE 'reset-it-%')");
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("the code sets a new password, and the old one stops working")
    void resetChangesThePassword() {
        String email = register();
        request(email);

        assertThat(confirm(email, codeFromLastEmail(), NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login(email, OLD_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** If an attacker was already inside, the reset has to end that -- not merely change
     *  what they would need to get back in. */
    @Test
    @DisplayName("every existing session is revoked")
    void existingSessionsAreRevoked() {
        String email = register();
        String sessionBefore = sessionCookie(login(email, OLD_PASSWORD));
        assertThat(get("/bff/auth/me", sessionBefore).getStatusCode()).isEqualTo(HttpStatus.OK);

        request(email);
        confirm(email, codeFromLastEmail(), NEW_PASSWORD, null);

        assertThat(get("/bff/auth/me", sessionBefore).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The decision that matters most here. Without it, whoever compromises a mailbox owns
     * the account outright, and 2FA is bypassed by the recovery flow rather than
     * surviving it.
     */
    @Test
    @DisplayName("an account with 2FA needs a factor code too")
    void mfaIsStillRequired() {
        String email = register();
        String secret = enrolTotp(sessionCookie(login(email, OLD_PASSWORD)));
        request(email);
        String code = codeFromLastEmail();

        // Inbox access alone is not enough.
        assertThat(confirm(email, code, NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(confirm(email, code, NEW_PASSWORD,
                Totp.codeAt(secret, Instant.now().plusSeconds(30))).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("a wrong code changes nothing")
    void wrongCodeChangesNothing() {
        String email = register();
        request(email);

        assertThat(confirm(email, "000000", NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("too many wrong guesses burn the code, even the right one")
    void guessingBurnsTheCode() {
        String email = register();
        request(email);
        String correct = codeFromLastEmail();

        for (int attempt = 0; attempt < 5; attempt++) {
            confirm(email, "000000", NEW_PASSWORD, null);
        }

        assertThat(confirm(email, correct, NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, OLD_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a code is single use")
    void codeCannotBeReused() {
        String email = register();
        request(email);
        String code = codeFromLastEmail();
        confirm(email, code, NEW_PASSWORD, null);

        assertThat(confirm(email, code, "yet-another-password-x", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Silent, not a 429: this endpoint answers identically for an unknown address, and
     *  reporting a cooldown would say "that address is registered and asked recently". */
    @Test
    @DisplayName("requesting again immediately sends nothing, but still answers 204")
    void requestIsRateLimitedSilently() {
        String email = register();
        request(email);
        assertThat(mailer.count()).isEqualTo(1);

        assertThat(request(email).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(mailer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("requesting again invalidates the previous code")
    void resendInvalidatesThePreviousCode() {
        String email = register();
        request(email);
        String first = codeFromLastEmail();
        expireCooldown(email);
        request(email);
        String second = codeFromLastEmail();
        assertThat(second).isNotEqualTo(first);

        assertThat(confirm(email, first, NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(confirm(email, second, NEW_PASSWORD, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Answering differently would make this a way to test which addresses have an
     *  account at a crypto exchange. */
    @Test
    @DisplayName("requesting for an unknown address is indistinguishable")
    void requestRevealsNothing() {
        String registered = register();
        mailer.clear();

        ResponseEntity<Map> known = request(registered);
        ResponseEntity<Map> unknown = post("/bff/auth/password-reset",
                Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com"), null);

        assertThat(unknown.getStatusCode()).isEqualTo(known.getStatusCode());
        assertThat(unknown.getBody()).isEqualTo(known.getBody());
        assertThat(mailer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the email carries a code and no link")
    void theEmailCarriesNoLink() {
        register();
        request(lastRegisteredEmail);

        assertThat(mailer.lastBody()).doesNotContain("http://").doesNotContain("https://");
        assertThat(mailer.lastBody()).containsPattern("\\b\\d{6}\\b");
    }

    // ------------------------------------------------------------------ helpers

    private String lastRegisteredEmail;

    private String register() {
        String email = "reset-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> response = post("/bff/auth/register",
                Map.of("email", email, "password", OLD_PASSWORD, "name", "IT"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        createdUserIds.add(((Number) data.get("id")).longValue());
        mailer.clear();
        lastRegisteredEmail = email;
        return email;
    }

    private ResponseEntity<Map> request(String email) {
        return post("/bff/auth/password-reset", Map.of("email", email), null);
    }

    private ResponseEntity<Map> confirm(String email, String code, String newPassword,
                                        String mfaCode) {
        Map<String, String> payload = new java.util.HashMap<>(Map.of(
                "email", email, "code", code, "newPassword", newPassword));
        if (mfaCode != null) {
            payload.put("mfaCode", mfaCode);
        }
        return post("/bff/auth/password-reset/confirm", payload, null);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return post("/bff/auth/login", Map.of("email", email, "password", password), null);
    }

    private String enrolTotp(String session) {
        @SuppressWarnings("unchecked")
        Map<String, Object> started = (Map<String, Object>) post("/bff/mfa/totp",
                Map.of(), session).getBody().get("data");
        String secret = (String) started.get("secret");
        post("/bff/mfa/totp/confirm",
                Map.of("code", Totp.codeAt(secret, Instant.now())), session);
        mailer.clear();
        return secret;
    }

    /** Backdates the live row so the 60s cooldown has notionally passed -- see the
     *  cooldown test above, which asserts it for real. */
    private void expireCooldown(String email) {
        jdbc.update("UPDATE user_password_reset_codes c JOIN user_users u ON u.id = c.user_id "
                + "SET c.created_at = c.created_at - INTERVAL 2 MINUTE "
                + "WHERE u.email = ? AND c.used_at IS NULL", email);
    }

    private String codeFromLastEmail() {
        String body = mailer.lastBody();
        assertThat(body).as("no email was sent").isNotNull();
        Matcher matcher = Pattern.compile("\\b(\\d{6})\\b").matcher(body);
        assertThat(matcher.find()).as("no six-digit code in: %s", body).isTrue();
        return matcher.group(1);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, String session) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(payload, headers(session)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String session) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(session)), Map.class);
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
