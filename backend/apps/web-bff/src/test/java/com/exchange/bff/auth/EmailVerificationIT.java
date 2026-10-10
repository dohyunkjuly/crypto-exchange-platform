package com.exchange.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.common.mail.Mailer;
import com.exchange.bff.testsupport.SeededDataCleaner;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
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

/**
 * Registration through to a confirmed address, over real HTTP.
 *
 * <p>The code is taken from a captured email rather than read out of the database, because
 * the database only holds its hash. A test that reached into the table would prove a row
 * exists; this proves the digits that actually reach a person work.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class EmailVerificationIT {

    private static final String PASSWORD = "correct-horse-battery";

    /** Stands in for the mailer so a test can read what would have been sent. Marked
     *  {@code @Primary} so it wins over {@code LoggingMailer} without disabling it. */
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
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("registering sends a code, and the code confirms the address")
    void registrationSendsACodeThatWorks() {
        String email = register();
        assertThat(mailer.count()).isEqualTo(1);
        String session = login(email);

        assertThat(post("/bff/auth/verify-email", Map.of("code", codeFromLastEmail()),
                session).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(isVerified(email)).isTrue();
    }

    /**
     * The property the whole choice rests on. An exchange that mails links teaches its
     * users that a link from us is normal, which is exactly what every phishing campaign
     * against exchange customers relies on.
     */
    @Test
    @DisplayName("the email contains a code and no link at all")
    void theEmailCarriesNoLink() {
        register();
        String body = mailer.lastBody();

        assertThat(body).doesNotContain("http://").doesNotContain("https://");
        assertThat(body).containsPattern("\\b\\d{6}\\b");
        // The rule has to appear on the genuine message for its absence to mean anything
        // on a fake one.
        assertThat(body).contains("never send you a link");
    }

    @Test
    @DisplayName("a code is single use")
    void codeCannotBeRedeemedTwice() {
        String email = register();
        String session = login(email);
        String code = codeFromLastEmail();
        post("/bff/auth/verify-email", Map.of("code", code), session);

        ResponseEntity<Map> replay =
                post("/bff/auth/verify-email", Map.of("code", code), session);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(replay.getBody().get("code")).isEqualTo(2014);
    }

    @Test
    @DisplayName("a wrong code is refused")
    void wrongCodeIsRefused() {
        String email = register();
        String session = login(email);

        ResponseEntity<Map> response =
                post("/bff/auth/verify-email", Map.of("code", "000000"), session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code")).isEqualTo(2014);
        assertThat(isVerified(email)).isFalse();
    }

    /**
     * Six digits is one of a million, so the cap is what makes a short code survivable at
     * all -- without it this endpoint is a million free guesses.
     */
    @Test
    @DisplayName("too many wrong guesses burn the code, even the right one")
    void guessingBurnsTheCode() {
        String email = register();
        String session = login(email);
        String correct = codeFromLastEmail();

        for (int attempt = 0; attempt < 5; attempt++) {
            post("/bff/auth/verify-email", Map.of("code", "000000"), session);
        }

        assertThat(post("/bff/auth/verify-email", Map.of("code", correct), session)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(isVerified(email)).isFalse();
    }

    @Test
    @DisplayName("resending immediately is refused, so one inbox cannot be buried")
    void resendIsRateLimited() {
        String email = register();
        String session = login(email);

        ResponseEntity<Map> tooSoon = post("/bff/auth/verify-email/resend", Map.of(), session);

        assertThat(tooSoon.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(tooSoon.getBody().get("code")).isEqualTo(2021);
        // And nothing extra was sent.
        assertThat(mailer.count()).isEqualTo(1);
    }

    /** Otherwise "resend" widens the window instead of replacing it, and every code ever
     *  mailed stays live -- each one another million-to-one draw for free. */
    @Test
    @DisplayName("resending invalidates the previous code")
    void resendInvalidatesThePreviousCode() {
        String email = register();
        String session = login(email);
        String first = codeFromLastEmail();
        expireCooldown("user_email_verification_tokens", email);

        assertThat(post("/bff/auth/verify-email/resend", Map.of(), session)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String second = codeFromLastEmail();
        assertThat(second).isNotEqualTo(first);

        assertThat(post("/bff/auth/verify-email", Map.of("code", first), session)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post("/bff/auth/verify-email", Map.of("code", second), session)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Requiring a session is what binds inbox to account -- and, incidentally, removes
     *  the enumeration problem an address-taking endpoint would have had. */
    @Test
    @DisplayName("verifying requires a session")
    void verifyingRequiresASession() {
        assertThat(post("/bff/auth/verify-email", Map.of("code", "123456"), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ------------------------------------------------------------------ helpers

    private String register() {
        String email = "verify-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> response = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        createdUserIds.add(((Number) data.get("id")).longValue());
        return email;
    }

    /** Backdates the live row so the 60s cooldown has notionally passed. Waiting a real
     *  minute per test is not a trade worth making, and the cooldown itself is asserted
     *  separately above. */
    private void expireCooldown(String table, String email) {
        jdbc.update("UPDATE " + table + " t JOIN user_users u ON u.id = t.user_id "
                + "SET t.created_at = t.created_at - INTERVAL 2 MINUTE "
                + "WHERE u.email = ? AND t.used_at IS NULL", email);
    }

    private String codeFromLastEmail() {
        String body = mailer.lastBody();
        assertThat(body).as("no email was sent").isNotNull();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(body);
        assertThat(matcher.find()).as("no six-digit code in: %s", body).isTrue();
        return matcher.group(1);
    }

    private String login(String email) {
        ResponseEntity<Map> response = post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null);
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        return cookies.get(0).split(";", 2)[0];
    }

    private boolean isVerified(String email) {
        Boolean verified = jdbc.queryForObject(
                "SELECT email_verified FROM user_users WHERE email = ?", Boolean.class, email);
        return Boolean.TRUE.equals(verified);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload) {
        return post(path, payload, null);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }
}
