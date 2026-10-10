package com.exchange.bff.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
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
 * Creating an API key needs a 2FA code, not just a session with 2FA enabled.
 *
 * <p>The distinction is the whole point. A stolen session can already read balances and
 * place orders, but a key it mints keeps trading after that session is revoked and the
 * password changed -- it is the one action in this app that hands out a credential
 * outliving the thing used to request it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class ApiKeyStepUpIT {

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

    @Autowired
    private UserRepository users;

    @Autowired
    private KycService kyc;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeSeededData() {
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("a correct code mints the key")
    void correctCodeMintsTheKey() {
        Account account = eligibleAccount();

        ResponseEntity<Map> response = createKey(account, nextCode(account.secret()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(response).get("secret")).isNotNull();
    }

    @Test
    @DisplayName("a wrong code mints nothing")
    void wrongCodeIsRefused() {
        Account account = eligibleAccount();

        ResponseEntity<Map> response = createKey(account, "000000");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code")).isEqualTo(2009);
        assertThat(keyCount(account.userId())).isZero();
    }

    /**
     * The reason step-up exists at all: everything else about this request is valid --
     * verified to INTERMEDIATE, 2FA enrolled, a live session -- and it still fails without
     * proof that whoever is asking holds the device now.
     */
    @Test
    @DisplayName("an eligible account with a live session still cannot mint one blind")
    void sessionAloneIsNotEnough() {
        Account account = eligibleAccount();

        ResponseEntity<Map> response = rest.exchange(url("/bff/api-keys"), HttpMethod.POST,
                new HttpEntity<>(Map.of("label", "bot", "canRead", true, "canTrade", true),
                        headers(account.session())), Map.class);

        // No mfaCode at all -- rejected by validation before anything is minted.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(keyCount(account.userId())).isZero();
    }

    /** A TOTP step is consumed on use, so the second key needs the next code. Surprising
     *  enough to pin: it means a client cannot batch two creations on one code. */
    @Test
    @DisplayName("the same code cannot mint a second key")
    void codeCannotBeReusedForASecondKey() {
        Account account = eligibleAccount();
        String code = nextCode(account.secret());
        assertThat(createKey(account, code).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> replay = createKey(account, code);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(keyCount(account.userId())).isEqualTo(1);
    }

    /** Widening what a key may do is minting a credential in all but name, so it wants the
     *  phone too; and the key itself does not change, so a bot holding it needs no new secret. */
    @Test
    @DisplayName("editing a key needs a fresh code, keeps the key, and changes only what was asked")
    void editingNeedsACodeAndKeepsTheKey() {
        Account account = eligibleAccount();
        Map<String, Object> created = data(createKey(account, Totp.codeAt(account.secret(), Instant.now())));
        String keyId = (String) created.get("id");

        Map<String, Object> edit = Map.of("label", "renamed", "canTrade", false, "canWithdraw", false,
                "ipAllowlist", List.of("10.0.0.0/8"), "mfaCode", "000000");
        ResponseEntity<Map> wrongCode = rest.exchange(url("/bff/api-keys/" + keyId), HttpMethod.PATCH,
                new HttpEntity<>(edit, headers(account.session())), Map.class);
        assertThat(wrongCode.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> right = new java.util.HashMap<>(edit);
        right.put("mfaCode", nextCode(account.secret()));
        Map<String, Object> edited = data(rest.exchange(url("/bff/api-keys/" + keyId), HttpMethod.PATCH,
                new HttpEntity<>(right, headers(account.session())), Map.class));
        assertThat(edited.get("label")).isEqualTo("renamed");
        assertThat(edited.get("canTrade")).isEqualTo(false);
        assertThat(edited.get("ipAllowlist")).isEqualTo(List.of("10.0.0.0/8"));
        assertThat(edited.get("apiKey")).isEqualTo(created.get("apiKey"));
    }

    /** A key would carry trading on around a suspension, so a trading one is refused; one that
     *  only reads is not, since a suspended customer may still watch their balances. */
    @Test
    @DisplayName("while trading is suspended, a trading key is refused and a read-only one is not")
    void suspendedTradingRefusesATradingKey() {
        Account account = eligibleAccount();
        jdbc.update("UPDATE user_users SET trading_suspended_at = NOW(6), trading_suspended_reason = 'review' WHERE id = ?",
                account.userId());

        ResponseEntity<Map> trading = createKey(account, nextCode(account.secret()));
        assertThat(trading.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(trading.getBody().get("code")).isEqualTo(2031);
        assertThat((String) trading.getBody().get("message")).doesNotContainIgnoringCase("suspend");
        assertThat(keyCount(account.userId())).isZero();

        ResponseEntity<Map> readOnly = post("/bff/api-keys", Map.of("label", "watcher", "canRead", true,
                "canTrade", false, "mfaCode", nextCode(account.secret())), account.session());
        assertThat(readOnly.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Killing a leaked key must not depend on having your phone -- the minutes when it
     *  matters most are exactly when you might not. */
    @Test
    @DisplayName("revoking needs no code")
    void revokingNeedsNoCode() {
        Account account = eligibleAccount();
        // A ULID string on the wire, never a counted number: a counter would tell anyone with two
        // keys how many the exchange issued in between (V15).
        String keyId = (String) data(createKey(account, nextCode(account.secret()))).get("id");
        assertThat(UUID.fromString(keyId)).isNotNull();

        ResponseEntity<Void> revoked = rest.exchange(url("/bff/api-keys/" + keyId),
                HttpMethod.DELETE, new HttpEntity<>(headers(account.session())), Void.class);

        // 204, and no code was supplied.
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // ------------------------------------------------------------------ helpers

    private record Account(long userId, String session, String secret) {
    }

    /** Verified to INTERMEDIATE with 2FA enrolled: everything the key endpoint asks for
     *  except the code itself. */
    private Account eligibleAccount() {
        String email = "apikey-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        long userId = ((Number) data(registered).get("id")).longValue();
        createdUserIds.add(userId);

        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));

        User user = users.findById(userId).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        kyc.approve(userId, KycLevel.INTERMEDIATE, null);

        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        // One step back, inside the drift the server allows: it leaves "now" and the next step
        // unspent, so a test can prove two codes in a row without waiting thirty seconds.
        post("/bff/mfa/totp/confirm",
                Map.of("code", Totp.codeAt(secret, Instant.now().minusSeconds(30))), session);
        return new Account(userId, session, secret);
    }

    /** Confirming the enrolment just spent the current step, so the next one is what a
     *  user reading their phone would actually type. */
    private String nextCode(String secret) {
        return Totp.codeAt(secret, Instant.now().plusSeconds(30));
    }

    private ResponseEntity<Map> createKey(Account account, String mfaCode) {
        return post("/bff/api-keys", Map.of("label", "bot", "canRead", true,
                "canTrade", true, "mfaCode", mfaCode), account.session());
    }

    private int keyCount(long userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_api_keys WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, String session) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(payload, headers(session)), Map.class);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
