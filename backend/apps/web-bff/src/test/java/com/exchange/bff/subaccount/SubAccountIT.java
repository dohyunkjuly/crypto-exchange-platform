package com.exchange.bff.subaccount;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
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
 * A master makes a sub, funds it, mints it a key, freezes it: the whole feature over HTTP,
 * against the real database. docs/reference/sub-accounts.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class SubAccountIT {

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092, 27018}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("port " + port + " not reachable -- run `docker compose up -d`");
            }
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
    @Autowired
    private BalanceService balances;
    @Autowired
    private AssetService assets;
    @Autowired
    private EligibilityService eligibility;

    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeSeededData() {
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    @Test
    @DisplayName("create, fund by transfer, mint a key with the master's code, freeze: and a frozen sub takes no funds")
    void theWholeFeature() {
        Account master = eligibleAccount();
        long usdt = assets.getBySymbol("USDT").getId();
        balances.credit(master.userId(), usdt, 1_000_00000000L, ReferenceType.ADMIN, "it", "it:" + master.userId());

        Map<String, Object> sub = data(post("/bff/sub-accounts", Map.of("label", "arb bot"), master.session()));
        long subId = ((Number) sub.get("id")).longValue();
        assertThat(sub.get("label")).isEqualTo("arb bot");
        assertThat((String) sub.get("email")).contains("_virtual@").doesNotContain(master.email());
        assertThat(sub.get("frozen")).isEqualTo(false);
        User subRow = users.findById(subId).orElseThrow();
        assertThat(subRow.getPasswordHash()).isNull();
        assertThat(eligibility.canTrade(subRow)).as("gated by its master, who may trade").isTrue();

        // As a long: a ten-digit id does not fit an int (RandomUidGenerator).
        assertThat(list(get("/bff/sub-accounts", master.session()))).extracting(s -> ((Number) s.get("id")).longValue())
                .containsExactly(subId);

        // Funds move at once, both ledgers written, and a replay with the same id moves nothing twice.
        String transferId = UUID.randomUUID().toString();
        Map<String, ?> transfer = Map.of("fromUserId", master.userId(), "toUserId", subId, "asset", "USDT",
                "amount", "250", "transferId", transferId);
        assertThat(post("/bff/sub-accounts/transfers", transfer, master.session()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(post("/bff/sub-accounts/transfers", transfer, master.session()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        List<Map<String, Object>> subBalances = list(get("/bff/sub-accounts/" + subId + "/balances", master.session()));
        assertThat(subBalances).singleElement().satisfies(b -> {
            assertThat(b.get("asset")).isEqualTo("USDT");
            assertThat(b.get("available")).isEqualTo("250");
        });
        assertThat(list(get("/bff/sub-accounts/transfers", master.session()))).hasSize(1);
        // The same balances in one call, keyed by sub, for a screen that lists every sub at once.
        Map<String, Object> allBalances = (Map<String, Object>) get("/bff/sub-accounts/balances",
                master.session()).getBody().get("data");
        assertThat(allBalances).containsOnlyKeys(String.valueOf(subId));
        assertThat((List<Map<String, Object>>) allBalances.get(String.valueOf(subId))).singleElement()
                .satisfies(b -> assertThat(b.get("available")).isEqualTo("250"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wallet_ledger_entries WHERE reference_type = 'TRANSFER' "
                + "AND user_id IN (?, ?)", Integer.class, master.userId(), subId)).isEqualTo(2);
        // Ledgered as a transfer, never as a fee: the fee sweep reads FEE rows as revenue.
        assertThat(jdbc.queryForList("SELECT entry_type FROM wallet_ledger_entries WHERE reference_type = 'TRANSFER' "
                + "AND user_id IN (?, ?) ORDER BY entry_type", String.class, master.userId(), subId))
                .containsExactly("TRANSFER_IN", "TRANSFER_OUT");

        // More than the sender has: refused, and nothing moved.
        ResponseEntity<Map> tooMuch = post("/bff/sub-accounts/transfers", Map.of("fromUserId", subId,
                "toUserId", master.userId(), "asset", "USDT", "amount", "251"), master.session());
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooMuch.getBody().get("code")).isEqualTo(4001);

        // A key for the sub, minted with the master's code; it belongs to the sub.
        Map<String, Object> key = data(post("/bff/sub-accounts/" + subId + "/api-keys",
                Map.of("label", "bot", "canRead", true, "canTrade", true,
                        "mfaCode", Totp.codeAt(master.secret(), Instant.now().plusSeconds(30))), master.session()));
        assertThat(key.get("secret")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_api_keys WHERE api_key = ?", Long.class,
                key.get("apiKey"))).isEqualTo(subId);
        assertThat(list(get("/bff/sub-accounts/" + subId + "/api-keys", master.session()))).hasSize(1);
        // And every sub's keys in one call, for the screen that lists all of them at once.
        Map<String, Object> allKeys = (Map<String, Object>) get("/bff/sub-accounts/api-keys",
                master.session()).getBody().get("data");
        assertThat(allKeys).containsOnlyKeys(String.valueOf(subId));
        assertThat((List<Map<String, Object>>) allKeys.get(String.valueOf(subId))).singleElement()
                .satisfies(k -> {
                    assertThat(k.get("label")).isEqualTo("bot");
                    assertThat(k).as("a secret is never listed").doesNotContainKey("secret");
                });

        // Frozen: cannot trade, cannot be sent funds; listed as frozen.
        Map<String, Object> frozen = data(rest.exchange(url("/bff/sub-accounts/" + subId + "/frozen"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("frozen", true), headers(master.session())), Map.class));
        assertThat(frozen.get("frozen")).isEqualTo(true);
        assertThat(eligibility.canTrade(users.findById(subId).orElseThrow())).isFalse();
        ResponseEntity<Map> toFrozen = post("/bff/sub-accounts/transfers", Map.of("fromUserId", master.userId(),
                "toUserId", subId, "asset", "USDT", "amount", "1"), master.session());
        assertThat(toFrozen.getBody().get("code")).isEqualTo(2030);

        // Nothing turns a sub into a session: not the password flow, not a reset its master's
        // inbox would receive. Both answer as if the address did not exist, and no code is made.
        String subEmail = (String) sub.get("email");
        assertThat(post("/bff/auth/login", Map.of("email", subEmail, "password", PASSWORD), null)
                .getBody().get("code")).isEqualTo(2001);
        assertThat(post("/bff/auth/password-reset", Map.of("email", subEmail), null).getStatusCode().is2xxSuccessful())
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_password_reset_codes WHERE user_id = ?",
                Integer.class, subId)).isZero();

        // Another master sees none of it.
        Account stranger = eligibleAccount();
        assertThat(get("/bff/sub-accounts/" + subId + "/balances", stranger.session()).getBody().get("code"))
                .isEqualTo(2029);
    }

    // ------------------------------------------------------------------ helpers

    private record Account(long userId, String email, String session, String secret) {
    }

    private Account eligibleAccount() {
        String email = "sub-it-" + UUID.randomUUID() + "@example.com";
        long userId = ((Number) data(post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null)).get("id")).longValue();
        createdUserIds.add(userId);
        String session = sessionCookie(post("/bff/auth/login", Map.of("email", email, "password", PASSWORD), null));
        User user = users.findById(userId).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        kyc.approve(userId, KycLevel.INTERMEDIATE, null);
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        post("/bff/mfa/totp/confirm", Map.of("code", Totp.codeAt(secret, Instant.now())), session);
        return new Account(userId, email, session, secret);
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
        return response.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody()).isTrue();
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
