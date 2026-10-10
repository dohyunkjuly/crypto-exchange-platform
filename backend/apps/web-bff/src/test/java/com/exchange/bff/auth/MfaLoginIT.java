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
 * Two-phase login over real HTTP: enrol a factor, then prove that a password alone stops
 * being enough.
 *
 * <p>The assertion that matters most is the negative one -- that the first call returns
 * <b>no session cookie</b> when 2FA is on. Until this existed, {@code mfa_enabled} was a
 * flag the trading gate read while the second factor was never actually demanded, so a
 * stolen password still opened everything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class MfaLoginIT {

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
    @DisplayName("without 2FA, a password signs you straight in")
    @SuppressWarnings("unchecked")
    void passwordAloneSignsInWhenNoFactorEnrolled() {
        String email = register();

        ResponseEntity<Map> response = login(email);

        assertThat(body(response).get("mfaRequired")).isEqualTo(false);
        assertThat(sessionCookie(response)).isNotNull();
        assertThat((Map<String, Object>) body(response).get("user")).containsKey("email");
    }

    @Test
    @DisplayName("with 2FA, the password alone yields a challenge and no session")
    void passwordAloneIsNotEnoughOnceEnrolled() {
        String email = register();
        enrolTotp(sessionCookie(login(email)));

        ResponseEntity<Map> response = login(email);

        assertThat(body(response).get("mfaRequired")).isEqualTo(true);
        assertThat(body(response).get("challengeToken")).isNotNull();
        // The whole point: nothing is authenticated yet.
        assertThat(sessionCookie(response)).isNull();
        assertThat(body(response)).doesNotContainKey("user");
    }

    @Test
    @DisplayName("the code completes the login and mints the session")
    void codeCompletesTheLogin() {
        String email = register();
        Enrolment enrolment = enrolTotp(sessionCookie(login(email)));

        String challenge = (String) body(login(email)).get("challengeToken");
        ResponseEntity<Map> completed = postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge, "code", nextCode(enrolment.secret())),
                null);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionCookie(completed)).isNotNull();
        // And that session actually works.
        assertThat(get("/bff/auth/me", sessionCookie(completed)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a wrong code does not mint a session")
    void wrongCodeIsRejected() {
        String email = register();
        enrolTotp(sessionCookie(login(email)));

        String challenge = (String) body(login(email)).get("challengeToken");
        ResponseEntity<Map> response = postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge, "code", "000000"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo(2009);
        assertThat(sessionCookie(response)).isNull();
    }

    /** A challenge that minted a session must not mint a second one. */
    @Test
    @DisplayName("a challenge is single-use")
    void challengeCannotBeReplayed() {
        String email = register();
        Enrolment enrolment = enrolTotp(sessionCookie(login(email)));
        String challenge = (String) body(login(email)).get("challengeToken");
        String code = nextCode(enrolment.secret());

        assertThat(postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge, "code", code), null)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> replay = postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge, "code", code), null);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(replay.getBody().get("code")).isEqualTo(2013);
    }

    @Test
    @DisplayName("an unknown challenge is rejected as expired, not as a bad code")
    void unknownChallengeIsRejected() {
        ResponseEntity<Map> response = postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", "not-a-real-challenge", "code", "123456"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo(2013);
    }

    /** A recovery code has to work at the login prompt, or it rescues nobody: the whole
     *  scenario is a user who cannot produce a TOTP code. */
    @Test
    @DisplayName("a recovery code completes the login too")
    void recoveryCodeCompletesTheLogin() {
        String email = register();
        Enrolment enrolment = enrolTotp(sessionCookie(login(email)));
        assertThat(enrolment.recoveryCodes()).hasSize(10);

        String challenge = (String) body(login(email)).get("challengeToken");
        ResponseEntity<Map> completed = postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge,
                        "code", enrolment.recoveryCodes().get(0)), null);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionCookie(completed)).isNotNull();
    }

    /**
     * Recovery codes are a supply that only runs down: issued once at enrolment, spent one
     * at a time. A user who burns the last one and then loses their authenticator has no
     * way back in -- disabling 2FA demands the factor they no longer have. Regeneration is
     * the only self-service exit, so it has to genuinely <b>replace</b> the set, not add to
     * it, and the new codes have to work at the login prompt where they are needed.
     */
    @Test
    @DisplayName("regenerating retires every old code and the new ones rescue the account")
    @SuppressWarnings("unchecked")
    void regeneratedRecoveryCodesReplaceTheOldSet() {
        String email = register();
        String session = sessionCookie(login(email));
        Enrolment enrolment = enrolTotp(session);
        String retired = enrolment.recoveryCodes().get(0);

        ResponseEntity<Map> reissued = postJson("/bff/mfa/recovery-codes",
                Map.of("code", nextCode(enrolment.secret())), session);
        List<String> fresh = (List<String>) body(reissued).get("recoveryCodes");
        assertThat(fresh).hasSize(10)
                .doesNotHaveDuplicates()
                .doesNotContainAnyElementsOf(enrolment.recoveryCodes());
        assertThat(body(get("/bff/mfa", session)).get("recoveryCodesRemaining"))
                .isEqualTo(10);

        // Replaced, not added to: an old code still opening the account would mean
        // regeneration doubles the number of credentials instead of rotating them.
        ResponseEntity<Map> withRetired = completeLogin(email, retired);
        assertThat(withRetired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(withRetired.getBody().get("code")).isEqualTo(2009);
        assertThat(sessionCookie(withRetired)).isNull();

        // And the other half: a new code has to actually get the user in.
        ResponseEntity<Map> withFresh = completeLogin(email, fresh.get(0));
        assertThat(withFresh.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionCookie(withFresh)).isNotNull();
    }

    /** A stolen session must not be able to rotate the codes out from under their owner --
     *  that would lock the real user out while leaving the thief a full fresh sheet. */
    @Test
    @DisplayName("regenerating with a wrong code changes nothing")
    void regenerationRequiresPassingTheFactor() {
        String email = register();
        String session = sessionCookie(login(email));
        Enrolment enrolment = enrolTotp(session);

        ResponseEntity<Map> refused = postJson("/bff/mfa/recovery-codes",
                Map.of("code", "000000"), session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getBody().get("code")).isEqualTo(2009);
        // The codes the user already holds must survive a failed attempt, or a mistyped
        // digit becomes the lockout this whole operation exists to prevent.
        ResponseEntity<Map> stillWorks =
                completeLogin(email, enrolment.recoveryCodes().get(0));
        assertThat(stillWorks.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionCookie(stillWorks)).isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    private String register() {
        String email = "mfa-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = postJson("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdUserIds.add(((Number) body(registered).get("id")).longValue());
        return email;
    }

    private ResponseEntity<Map> login(String email) {
        return postJson("/bff/auth/login", Map.of("email", email, "password", PASSWORD), null);
    }

    /** The secret an enrolment produced, plus the recovery codes it issued -- both shown
     *  exactly once, so the helper has to hand back both from the single confirm. */
    private record Enrolment(String secret, List<String> recoveryCodes) {
    }

    /** Runs the real two-call enrolment, so a broken enrolment fails these tests rather
     *  than being papered over by writing a verified row directly. */
    @SuppressWarnings("unchecked")
    private Enrolment enrolTotp(String session) {
        ResponseEntity<Map> started = postJson("/bff/mfa/totp", Map.of(), session);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secret = (String) body(started).get("secret");
        assertThat((String) body(started).get("provisioningUri")).startsWith("otpauth://totp/");

        ResponseEntity<Map> confirmed = postJson("/bff/mfa/totp/confirm",
                Map.of("code", Totp.codeAt(secret, Instant.now())), session);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new Enrolment(secret,
                (List<String>) body(confirmed).get("recoveryCodes"));
    }

    /**
     * The code an authenticator would be showing 30 seconds from now.
     *
     * <p>Not the current one: confirming the enrolment just spent this step, and a spent
     * step is refused as a replay -- which is the guard working, not a bug. The next code
     * is still inside the drift window, so this is what a real user typing the number
     * their phone has rolled to would send.
     */
    private String nextCode(String secret) {
        return Totp.codeAt(secret, Instant.now().plusSeconds(30));
    }

    /** A fresh challenge answered with the given code -- each call logs in from scratch,
     *  since a challenge is single-use. */
    private ResponseEntity<Map> completeLogin(String email, String code) {
        String challenge = (String) body(login(email)).get("challengeToken");
        return postJson("/bff/auth/login/mfa",
                Map.of("challengeToken", challenge, "code", code), null);
    }

    private ResponseEntity<Map> postJson(String path, Map<String, ?> payload, String session) {
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

    /** The Set-Cookie a response carries, or null when it sets none -- which is itself
     *  the assertion in half these tests. */
    private String sessionCookie(ResponseEntity<Map> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream()
                .filter(c -> c.startsWith("session=") && !c.startsWith("session=;"))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
