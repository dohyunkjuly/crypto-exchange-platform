package com.exchange.spot.openapi.subaccount;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.spot.openapi.common.security.ApiKeyAuthInterceptor;
import com.exchange.spot.openapi.common.security.HmacSignature;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.asset.AssetService;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The master's key manages its subs; a sub's own key is an ordinary account that manages
 * nothing; and a sub's key trades, gated by its master. Uses the dev seeder's alice as the
 * master, as OrdersIT does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=" + SubAccountsIT.ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class SubAccountsIT {

    static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String SECRET = "it-secret-" + UUID.randomUUID();

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 27018, 6379, 9092}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("port " + port + " not reachable -- run `docker compose up -d`");
            }
        }
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private UserApiKeyRepository keys;
    @Autowired private BalanceService balances;
    @Autowired private AssetService assets;
    @Autowired private JdbcTemplate jdbc;

    private User alice;
    private UserApiKey masterKey;
    private final List<UserApiKey> minted = new ArrayList<>();
    private final List<Long> subIds = new ArrayList<>();

    @BeforeEach
    void mintAKeyForAlice() {
        alice = users.findByEmail("alice@example.com").orElse(null);
        Assumptions.assumeTrue(alice != null, "the dev seeder's alice is not present -- start the stack with the dev profile");
        masterKey = mint(alice.getId(), true);
    }

    @AfterEach
    void cleanUp() {
        if (alice == null) {
            return; // the test was skipped before it made anything
        }
        minted.forEach(keys::delete);
        for (Long subId : subIds) {
            jdbc.update("DELETE FROM spot_command_outbox WHERE user_id = ?", subId);
            jdbc.update("DELETE FROM spot_orders WHERE user_id = ?", subId);
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id = ?", subId);
            jdbc.update("DELETE FROM wallet_balances WHERE user_id = ?", subId);
            jdbc.update("DELETE FROM wallet_transfers WHERE from_user_id = ? OR to_user_id = ?", subId, subId);
            jdbc.update("DELETE FROM user_users WHERE id = ?", subId);
        }
        jdbc.update("DELETE FROM user_auth_events WHERE user_id = ? AND event_type LIKE 'SUB_ACCOUNT_%'", alice.getId());
        jdbc.update("UPDATE user_users SET trading_suspended_at = NULL, trading_suspended_reason = NULL WHERE id = ?",
                alice.getId());
        jdbc.update("DELETE FROM fee_user_overrides WHERE user_id = ? AND reason = 'sub-it'", alice.getId());
    }

    @Test
    @DisplayName("the master's key creates, funds and freezes a sub; the sub's key manages nothing, but trades")
    void theWholeFeature() {
        ResponseEntity<JsonNode> created = call(masterKey, HttpMethod.POST, "/v1/sub-accounts", "{\"label\":\"bot 1\"}");
        assertThat(created.getStatusCode().value()).as(created.getBody().toString()).isEqualTo(201);
        long subId = created.getBody().get("data").get("subAccountId").asLong();
        subIds.add(subId);
        // Drawn at random, never counted: the next account made is not the next number.
        long next = call(masterKey, HttpMethod.POST, "/v1/sub-accounts", "{\"label\":\"bot 1b\"}")
                .getBody().get("data").get("subAccountId").asLong();
        subIds.add(next);
        assertThat(String.valueOf(subId)).hasSize(10);
        assertThat(Math.abs(next - subId)).isGreaterThan(1);

        // Fund the master, then move some down. The same transferId twice is one transfer.
        long usdt = assets.getBySymbol("USDT").getId();
        balances.credit(alice.getId(), usdt, 100_00000000L, ReferenceType.ADMIN, "sub-it", "sub-it:" + UUID.randomUUID());
        String transfer = "{\"fromUserId\":" + alice.getId() + ",\"toUserId\":" + subId + ",\"asset\":\"USDT\","
                + "\"amount\":\"40\",\"transferId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(call(masterKey, HttpMethod.POST, "/v1/sub-accounts/transfers", transfer).getStatusCode().value())
                .isEqualTo(201);
        assertThat(call(masterKey, HttpMethod.POST, "/v1/sub-accounts/transfers", transfer).getStatusCode().value())
                .isEqualTo(201);
        JsonNode subBalances = call(masterKey, HttpMethod.GET, "/v1/sub-accounts/" + subId + "/balances", "")
                .getBody().get("data");
        assertThat(subBalances.get(0).get("available").asText()).isEqualTo("40");

        // The sub's own key: an account like any other, and 2111 on anything here.
        UserApiKey subKey = mint(subId, true);
        assertThat(call(subKey, HttpMethod.GET, "/v1/sub-accounts", "").getBody().get("code").asInt()).isEqualTo(2111);
        assertThat(call(subKey, HttpMethod.GET, "/v1/wallet/balances", "").getBody().get("data").get(0)
                .get("available").asText()).isEqualTo("40");
        // Gated by its master, who may trade: a resting order far from any price.
        String order = "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\","
                + "\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        ResponseEntity<JsonNode> placed = call(subKey, HttpMethod.POST, "/v1/orders", order);
        assertThat(placed.getStatusCode().value()).as(placed.getBody().toString()).isEqualTo(202);
        String orderId = placed.getBody().get("data").get("orderId").asText();
        assertThat(call(subKey, HttpMethod.DELETE, "/v1/orders/" + orderId, "").getStatusCode().value()).isEqualTo(202);

        // Frozen by the master: the sub's next order is 2110, its cancels still work, and no funds reach it.
        assertThat(call(masterKey, HttpMethod.PATCH, "/v1/sub-accounts/" + subId + "/frozen", "{\"frozen\":true}")
                .getBody().get("data").get("frozen").asBoolean()).isTrue();
        assertThat(call(subKey, HttpMethod.POST, "/v1/orders", order).getBody().get("code").asInt()).isEqualTo(2110);
        // Built afresh, not edited from the one above: a random ten-digit id can contain any digits.
        String toFrozen = "{\"fromUserId\":" + alice.getId() + ",\"toUserId\":" + subId + ",\"asset\":\"USDT\","
                + "\"amount\":\"1\",\"transferId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(call(masterKey, HttpMethod.POST, "/v1/sub-accounts/transfers", toFrozen)
                .getBody().get("code").asInt()).isEqualTo(2114);

        // A read-only master key lists but cannot move money.
        UserApiKey readOnly = mint(alice.getId(), false);
        assertThat(call(readOnly, HttpMethod.GET, "/v1/sub-accounts", "").getStatusCode().value()).isEqualTo(200);
        assertThat(call(readOnly, HttpMethod.POST, "/v1/sub-accounts/transfers", transfer).getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a master's trading suspension stops its subs too, and every transfer, but not a cancel or a read -- and never says why")
    void tradingSuspended() {
        long subId = call(masterKey, HttpMethod.POST, "/v1/sub-accounts", "{\"label\":\"bot 2\"}")
                .getBody().get("data").get("subAccountId").asLong();
        subIds.add(subId);
        long usdt = assets.getBySymbol("USDT").getId();
        balances.credit(subId, usdt, 100_00000000L, ReferenceType.ADMIN, "sub-it", "sub-it:" + UUID.randomUUID());
        UserApiKey subKey = mint(subId, true);
        String order = "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\","
                + "\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        String resting = call(subKey, HttpMethod.POST, "/v1/orders", order).getBody().get("data").get("orderId").asText();

        jdbc.update("UPDATE user_users SET trading_suspended_at = NOW(6), trading_suspended_reason = 'review' WHERE id = ?",
                alice.getId());

        JsonNode refused = call(masterKey, HttpMethod.POST, "/v1/orders", order).getBody();
        assertThat(refused.get("code").asInt()).isEqualTo(2115);
        assertThat(refused.get("message").asText()).as("never says suspended, or why").doesNotContainIgnoringCase("suspend");
        assertThat(call(subKey, HttpMethod.POST, "/v1/orders", order).getBody().get("code").asInt())
                .as("the sub's own row is untouched; its master's suspension is enough").isEqualTo(2115);
        String transfer = "{\"fromUserId\":" + subId + ",\"toUserId\":" + alice.getId() + ",\"asset\":\"USDT\","
                + "\"amount\":\"1\",\"transferId\":\"" + UUID.randomUUID() + "\"}";
        assertThat(call(masterKey, HttpMethod.POST, "/v1/sub-accounts/transfers", transfer).getBody().get("code").asInt())
                .isEqualTo(2115);

        JsonNode account = call(subKey, HttpMethod.GET, "/v1/account", "").getBody().get("data");
        assertThat(account.get("canTrade").asBoolean()).isFalse();
        assertThat(account.has("tradingSuspended")).as("the account says it cannot trade, not why").isFalse();
        assertThat(call(subKey, HttpMethod.DELETE, "/v1/orders/" + resting, "").getStatusCode().value())
                .as("getting out is never gated").isEqualTo(202);

        jdbc.update("UPDATE user_users SET trading_suspended_at = NULL WHERE id = ?", alice.getId());
        assertThat(call(subKey, HttpMethod.GET, "/v1/account", "").getBody().get("data").get("canTrade").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("a sub is charged its master's fee, stamped on the order")
    void aSubPaysItsMastersFeeOverride() {
        Assumptions.assumeTrue(jdbc.queryForObject("SELECT COUNT(*) FROM fee_user_overrides WHERE user_id = ?",
                Integer.class, alice.getId()) == 0, "alice already has a fee override");
        long subId = call(masterKey, HttpMethod.POST, "/v1/sub-accounts", "{\"label\":\"bot 3\"}")
                .getBody().get("data").get("subAccountId").asLong();
        subIds.add(subId);
        balances.credit(subId, assets.getBySymbol("USDT").getId(), 100_00000000L, ReferenceType.ADMIN, "sub-it",
                "sub-it:" + UUID.randomUUID());
        jdbc.update("INSERT INTO fee_user_overrides (user_id, product, maker_fee_bps, taker_fee_bps, reason) "
                + "VALUES (?, 'SPOT', 3, 7, 'sub-it')", alice.getId());

        UserApiKey subKey = mint(subId, true);
        String order = "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\","
                + "\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        String orderId = call(subKey, HttpMethod.POST, "/v1/orders", order).getBody().get("data").get("orderId").asText();

        Map<String, Object> stamped = jdbc.queryForMap("SELECT maker_fee_bps, taker_fee_bps FROM spot_orders WHERE user_id = ?",
                subId);
        assertThat(((Number) stamped.get("maker_fee_bps")).intValue()).isEqualTo(3);
        assertThat(((Number) stamped.get("taker_fee_bps")).intValue()).isEqualTo(7);
        call(subKey, HttpMethod.DELETE, "/v1/orders/" + orderId, "");
    }

    private UserApiKey mint(Long userId, boolean canTrade) {
        byte[] encrypted = new AesGcmCipher(Base64.getDecoder().decode(ENCRYPTION_KEY))
                .encrypt(SECRET.getBytes(StandardCharsets.UTF_8));
        UserApiKey key = UserApiKey.hmac(userId, "sub-it", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        key.setCanRead(true);
        key.setCanTrade(canTrade);
        key = keys.save(key);
        minted.add(key);
        return key;
    }

    private ResponseEntity<JsonNode> call(UserApiKey with, HttpMethod method, String pathWithQuery, String body) {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set(ApiKeyAuthInterceptor.KEY_HEADER, with.getApiKey());
        headers.set(ApiKeyAuthInterceptor.TIMESTAMP_HEADER, ts);
        headers.set(ApiKeyAuthInterceptor.SIGNATURE_HEADER, HmacSignature.sign(SECRET,
                HmacSignature.canonical(ts, method.name(), pathWithQuery, body.getBytes(StandardCharsets.UTF_8))));
        return rest.exchange("http://localhost:" + port + pathWithQuery, method,
                new HttpEntity<>(body.isEmpty() ? null : body, headers), JsonNode.class);
    }
}
