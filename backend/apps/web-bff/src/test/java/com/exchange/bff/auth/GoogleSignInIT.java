package com.exchange.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.auth.google.GoogleIdentity;
import com.exchange.bff.auth.google.GoogleTokenVerifier;
import com.exchange.bff.auth.google.InvalidGoogleTokenException;
import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
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
 * Google sign-in, with the token verification stubbed.
 *
 * <p>The stub is the point, not a shortcut: a test that reached Google would need network
 * access and a real signed token, and would then be testing Google rather than the
 * decisions made here -- which account a token maps to, and when we are willing to attach
 * one to an account that already exists.
 *
 * <p>{@code NimbusGoogleTokenVerifier} itself is not exercised by this. What it does --
 * signature, issuer and audience -- is Nimbus's job plus three lines of configuration, and
 * proving it would mean minting tokens with a key Google does not have.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class GoogleSignInIT {

    private static final String PASSWORD = "correct-horse-battery";

    @TestConfiguration
    static class StubbedVerifierConfig {
        @Bean
        @Primary
        StubVerifier stubVerifier() {
            return new StubVerifier();
        }
    }

    /** Maps a fake token string to the identity it stands for. */
    static class StubVerifier implements GoogleTokenVerifier {

        private final Map<String, GoogleIdentity> identities = new ConcurrentHashMap<>();

        String issue(String subject, String email, boolean emailVerified) {
            String token = "stub-" + UUID.randomUUID();
            identities.put(token, new GoogleIdentity(subject, email, emailVerified));
            return token;
        }

        @Override
        public GoogleIdentity verify(String idToken) {
            GoogleIdentity identity = identities.get(idToken);
            if (identity == null) {
                throw new InvalidGoogleTokenException("unknown stub token");
            }
            return identity;
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
    private StubVerifier google;

    @Autowired
    private UserRepository users;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeSeededData() {
        jdbc.update("DELETE FROM user_external_identities WHERE user_id IN "
                + "(SELECT id FROM user_users WHERE email LIKE 'google-it-%')");
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("first sign-in creates an account, already verified")
    void firstSignInCreatesAnAccount() {
        String email = email();
        String token = google.issue("google-sub-1", email, true);

        ResponseEntity<Map> response = signIn(token);

        assertThat(data(response).get("mfaRequired")).isEqualTo(false);
        assertThat(sessionCookie(response)).isNotNull();

        User created = users.findByEmail(email).orElseThrow();
        createdUserIds.add(created.getId());
        // Google confirmed the address, so our own code flow would be asking them to
        // prove something already proven.
        assertThat(created.isEmailVerified()).isTrue();
        assertThat(created.getPasswordHash()).isNull();
        // And NO code was sent. Asking someone to confirm an address Google has already
        // confirmed proves nothing and is friction on the common path -- Google's
        // assertion is the proof. Pinned so it stays that way.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_email_verification_tokens WHERE user_id = ?",
                Integer.class, created.getId())).isZero();
    }

    /**
     * Rare in practice -- consumer Gmail is always verified -- but Google's own guidance
     * is to check the claim rather than assume it, and some Workspace configurations do
     * return false.
     */
    @Test
    @DisplayName("an unverified Google address makes an unverified account, and gets a code")
    void unverifiedGoogleAddressStillNeedsOurOwnVerification() {
        String email = email();

        ResponseEntity<Map> response = signIn(google.issue("google-sub-7", email, false));

        assertThat(sessionCookie(response)).isNotNull();
        User created = users.findByEmail(email).orElseThrow();
        createdUserIds.add(created.getId());
        // We do not inherit a verification Google itself would not assert.
        assertThat(created.isEmailVerified()).isFalse();
        // And a code was issued, so the account is not stranded being told to confirm an
        // address with nothing in the inbox.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_email_verification_tokens WHERE user_id = ?",
                Integer.class, created.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("signing in again reuses the same account, matched on subject")
    void secondSignInReusesTheAccount() {
        String email = email();
        signIn(google.issue("google-sub-2", email, true));
        long userId = users.findByEmail(email).orElseThrow().getId();
        createdUserIds.add(userId);

        // A different token, and a DIFFERENT email -- people change their Gmail address.
        // The subject is what identifies them.
        signIn(google.issue("google-sub-2", "changed-" + email, true));

        assertThat(users.findByEmail("changed-" + email)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_identities WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    /**
     * The account-takeover this rule exists to stop: an attacker registers the victim's
     * address with a password and never verifies it, then waits for the victim to click
     * "Sign in with Google".
     */
    @Test
    @DisplayName("refuses to link to an existing account whose email was never verified")
    void refusesToLinkToAnUnverifiedAccount() {
        String email = email();
        createdUserIds.add(registerWithPassword(email));

        ResponseEntity<Map> response = signIn(google.issue("google-sub-3", email, true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo(2019);
        // And nothing was linked.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_identities", Integer.class)).isZero();
    }

    @Test
    @DisplayName("links to an existing account when both sides verified the address")
    void linksWhenBothSidesVerified() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        markEmailVerified(userId);

        ResponseEntity<Map> response = signIn(google.issue("google-sub-4", email, true));

        assertThat(sessionCookie(response)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_identities WHERE user_id = ?",
                Integer.class, userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses to link when Google itself will not vouch for the address")
    void refusesWhenGoogleSaysUnverified() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        markEmailVerified(userId);

        ResponseEntity<Map> response = signIn(google.issue("google-sub-5", email, false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("code")).isEqualTo(2019);
    }

    /**
     * Google proving who someone is does not prove they hold the second factor. Skipping
     * the challenge here would make 2FA bypassable through their Google account, which is
     * exactly what a second factor is meant to survive.
     */
    @Test
    @DisplayName("an account with 2FA is still challenged")
    void mfaIsStillRequired() {
        String email = email();
        signIn(google.issue("google-sub-6", email, true));
        long userId = users.findByEmail(email).orElseThrow().getId();
        createdUserIds.add(userId);
        enrolTotp(sessionCookie(signIn(google.issue("google-sub-6", email, true))));

        ResponseEntity<Map> response = signIn(google.issue("google-sub-6", email, true));

        assertThat(data(response).get("mfaRequired")).isEqualTo(true);
        assertThat(sessionCookie(response)).isNull();
    }

    @Test
    @DisplayName("a token that does not verify is refused")
    void badTokenIsRefused() {
        ResponseEntity<Map> response = signIn("not-a-token-we-issued");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo(2017);
    }

    // ------------------------------------------------------------------ unlinking

    /**
     * The lockout this guard exists for. Google created the account, so there is no
     * password; unlinking would leave the user holding nothing at all.
     */
    @Test
    @DisplayName("an account Google created cannot unlink it -- there is nothing else to sign in with")
    void cannotUnlinkWhenGoogleIsTheOnlyWayIn() {
        String email = email();
        String session = sessionCookie(signIn(google.issue("google-sub-8", email, true)));
        long userId = users.findByEmail(email).orElseThrow().getId();
        createdUserIds.add(userId);
        assertThat(users.findById(userId).orElseThrow().getPasswordHash()).isNull();

        ResponseEntity<Map> refused =
                post("/bff/auth/google/unlink", Map.of("password", PASSWORD), session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code")).isEqualTo(2023);
        assertThat(linkCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("with a password in place, the right one unlinks and the password still signs in")
    @SuppressWarnings("unchecked")
    void unlinkSucceedsAndLeavesThePasswordWorking() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        markEmailVerified(userId);
        String session = sessionCookie(signIn(google.issue("google-sub-9", email, true)));
        assertThat((List<Map<String, Object>>) dataList(get("/bff/auth/identities", session)))
                .singleElement()
                .satisfies(i -> assertThat(i.get("provider")).isEqualTo("GOOGLE"));

        ResponseEntity<Map> unlinked =
                post("/bff/auth/google/unlink", Map.of("password", PASSWORD), session);

        assertThat(unlinked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(linkCount(userId)).isZero();
        assertThat(dataList(get("/bff/auth/identities", session))).isEmpty();
        // The whole justification for allowing it: the other credential still works.
        assertThat(sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null))).isNotNull();
    }

    @Test
    @DisplayName("a wrong password leaves the link in place")
    void unlinkWithAWrongPasswordChangesNothing() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        markEmailVerified(userId);
        String session = sessionCookie(signIn(google.issue("google-sub-10", email, true)));

        ResponseEntity<Map> refused =
                post("/bff/auth/google/unlink", Map.of("password", "not-the-password"), session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refused.getBody().get("code")).isEqualTo(2001);
        // A password that exists is not proof the user remembers it. Someone who set one
        // years ago and has used Google ever since must not be able to unlink on the
        // strength of the column being populated.
        assertThat(linkCount(userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("unlinking when nothing is linked is a 404, not a silent success")
    void unlinkWithNothingLinked() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));

        ResponseEntity<Map> response =
                post("/bff/auth/google/unlink", Map.of("password", PASSWORD), session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo(2022);
    }

    /** Unlinking is not a ban. Pinned because it reads like a bug otherwise: the button
     *  that attached Google in the first place still works, and has to. */
    @Test
    @DisplayName("signing in with Google again after unlinking re-links it")
    void googleCanBeLinkedAgainAfterUnlinking() {
        String email = email();
        long userId = registerWithPassword(email);
        createdUserIds.add(userId);
        markEmailVerified(userId);
        String session = sessionCookie(signIn(google.issue("google-sub-11", email, true)));
        post("/bff/auth/google/unlink", Map.of("password", PASSWORD), session);
        assertThat(linkCount(userId)).isZero();

        assertThat(sessionCookie(signIn(google.issue("google-sub-11", email, true))))
                .isNotNull();
        assertThat(linkCount(userId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String email() {
        return "google-it-" + UUID.randomUUID() + "@example.com";
    }

    private ResponseEntity<Map> signIn(String idToken) {
        return post("/bff/auth/google", Map.of("idToken", idToken), null);
    }

    private long registerWithPassword(String email) {
        ResponseEntity<Map> response = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) data(response).get("id")).longValue();
    }

    private void markEmailVerified(long userId) {
        User user = users.findById(userId).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
    }

    private void enrolTotp(String session) {
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        post("/bff/mfa/totp/confirm",
                Map.of("code", Totp.codeAt(secret, Instant.now())), session);
    }

    private int linkCount(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_external_identities WHERE user_id = ?",
                Integer.class, userId);
    }

    private ResponseEntity<Map> get(String path, String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
    }

    /** The envelope's {@code data} when it holds a list rather than an object. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return (List<Map<String, Object>>) response.getBody().get("data");
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
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }
}
