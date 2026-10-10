package com.exchange.spot.openapi.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.spot.openapi.common.security.ApiKeyAuthInterceptor;
import com.exchange.spot.openapi.common.security.HmacSignature;
import com.exchange.spot.openapi.testsupport.TestEnginePresence;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolCatalog;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
 * Order entry through the signed API, as an account and on a market of the test's own.
 *
 * <p>The account is verified and funded, so the gate and the balance lock are real; the market
 * is quoted in USDT like any other. Both are made here and removed afterwards, with the keys,
 * the orders and the funds: the database is shared with a developer's own trading, and a test
 * on a seeded account and BTCUSDT leaves orders in their book and events on the topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=" + OrdersIT.ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class OrdersIT {

    static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String SECRET = "it-secret-" + UUID.randomUUID();

    @BeforeAll
    static void requireInfrastructure() {
        for (var p : new Object[][] {{"mysql", 3308}, {"mongo", 27018}, {"redis", 6379}, {"kafka", 9092}}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", (int) p[1]), 500);
            } catch (Exception e) {
                Assumptions.abort(p[0] + " not reachable -- run `docker compose up -d`");
            }
        }
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private UserApiKeyRepository keys;
    @Autowired private AssetRepository assets;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private BalanceService balances;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SymbolCatalog catalog;

    // Six digits: a symbol is twenty characters at most.
    private final String suffix = Long.toString(System.nanoTime() % 1_000_000L);
    /** The test's market: tick 0.01, step 0.00001, as BTCUSDT's, so the same prices and sizes are valid on it. */
    private final String symbol = "OI" + suffix + "USDT";
    private Long traderId;
    private SpotSymbol market;
    private UserApiKey key;
    private UserApiKey readOnlyKey;

    @BeforeEach
    void makeTheAccountAndItsMarket() {
        Long usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long base = assets.save(new Asset("OI" + suffix, "orders test base", AssetType.CRYPTO, 8)).getId();
        SpotSymbol made = new SpotSymbol(symbol, base, usdt, 1_000_000L, 1_000L, 1_000L, 900_000_000_000L,
                1_000_000_000L, 0);
        made.setStatus(SymbolStatus.TRADING);
        market = markets.save(made);

        // What the gate asks of anyone who trades: a confirmed address, KYC and a second factor.
        User trader = new User("orders-it-" + suffix + "@x.dev", null);
        trader.setEmailVerified(true);
        trader.setKycLevel(KycLevel.INTERMEDIATE);
        trader.setMfaEnabled(true);
        traderId = users.save(trader).getId();
        balances.credit(traderId, usdt, 100_000_00000000L, ReferenceType.DEPOSIT, "orders-it", "orders-it:" + suffix);

        byte[] encrypted = new AesGcmCipher(Base64.getDecoder().decode(ENCRYPTION_KEY)).encrypt(SECRET.getBytes(StandardCharsets.UTF_8));
        key = UserApiKey.hmac(traderId, "orders-it", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        key.setCanRead(true);
        key.setCanTrade(true);
        key = keys.save(key);
        readOnlyKey = UserApiKey.hmac(traderId, "orders-it-ro", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        readOnlyKey.setCanRead(true);
        readOnlyKey.setCanTrade(false);
        readOnlyKey = keys.save(readOnlyKey);
    }

    /**
     * The market first: once it is gone, settlement skips whatever events are still on their
     * way for it, so the order rows can be removed from under them.
     */
    @AfterEach
    void removeWhatWasMade() {
        if (market != null) {
            jdbc.update("DELETE FROM spot_symbols WHERE id = ?", market.getId());
            jdbc.update("DELETE FROM spot_command_outbox WHERE symbol = ?", symbol);
            jdbc.update("DELETE f FROM spot_order_fill_log f JOIN spot_orders o ON o.id = f.order_id "
                    + "WHERE o.symbol_id = ?", market.getId());
            jdbc.update("DELETE FROM spot_orders WHERE symbol_id = ?", market.getId());
        }
        if (key != null) {
            keys.delete(key);
            keys.delete(readOnlyKey);
        }
        if (traderId != null) {
            jdbc.update("DELETE FROM wallet_ledger_entries WHERE user_id = ?", traderId);
            jdbc.update("DELETE FROM wallet_balances WHERE user_id = ?", traderId);
            jdbc.update("DELETE FROM user_auth_events WHERE user_id = ?", traderId);
            jdbc.update("DELETE FROM user_users WHERE id = ?", traderId);
        }
        if (market != null) {
            jdbc.update("DELETE FROM wallet_assets WHERE id = ?", market.getBaseAssetId());
        }
    }

    @Test
    @DisplayName("place, read back by id and in the open list, replay the client id, cancel by client id, cancel-all")
    void lifecycle() {
        String clientId = "it-" + UUID.randomUUID();
        String body = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\","
                + "\"price\":\"1000.00\",\"quantity\":\"0.02\",\"clientOrderId\":\"" + clientId + "\"}";
        ResponseEntity<JsonNode> placed = call(key, HttpMethod.POST, "/v1/orders", body);
        assertThat(placed.getStatusCode().value()).as(placed.getBody().toString()).isEqualTo(202);
        JsonNode data = placed.getBody().get("data");
        String orderId = data.get("orderId").asText();
        assertThat(data.get("clientOrderId").asText()).isEqualTo(clientId);
        assertThat(data.get("status").asText()).isEqualTo("NEW");

        JsonNode byId = call(key, HttpMethod.GET, "/v1/orders/" + orderId, "").getBody().get("data");
        assertThat(byId.get("symbol").asText()).isEqualTo(symbol);
        assertThat(byId.get("price").asText()).isEqualTo("1000");
        assertThat(byId.get("quantity").asText()).isEqualTo("0.02");
        assertThat(byId.get("triggerDirection").isNull()).isTrue();
        // The 202 is built from the row the transaction wrote, not from a read: it has to carry what
        // only the database knows and what only the symbol table knows all the same.
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("transactTime").asText()).isEqualTo(byId.get("createdAt").asText());

        JsonNode open = call(key, HttpMethod.GET, "/v1/orders/open?symbol=" + symbol, "").getBody().get("data");
        assertThat(open.findValuesAsText("orderId")).contains(orderId);

        // Idempotent on the client's id: the same order, not a second one.
        ResponseEntity<JsonNode> replay = call(key, HttpMethod.POST, "/v1/orders", body);
        assertThat(replay.getBody().get("data").get("orderId").asText()).isEqualTo(orderId);
        assertThat(replay.getBody().get("data").get("symbol").asText()).isEqualTo(symbol);

        ResponseEntity<JsonNode> canceled = call(key, HttpMethod.DELETE, "/v1/orders/by-client-id/" + clientId, "");
        assertThat(canceled.getStatusCode().value()).isEqualTo(202);
        assertThat(canceled.getBody().get("data").get("status").asText()).isEqualTo("CANCELING");

        ResponseEntity<JsonNode> all = call(key, HttpMethod.DELETE, "/v1/orders/open?symbol=" + symbol + "&scope=BOOK", "");
        assertThat(all.getStatusCode().value()).isEqualTo(202);
        assertThat(all.getBody().get("data").get("count").asInt()).isGreaterThanOrEqualTo(0);

        JsonNode history = call(key, HttpMethod.GET, "/v1/orders?symbol=" + symbol + "&size=20", "").getBody().get("data");
        assertThat(history.findValuesAsText("orderId")).contains(orderId);
        assertThat(call(key, HttpMethod.GET, "/v1/trades?symbol=" + symbol, "").getBody().get("data").isArray()).isTrue();
    }

    /**
     * The amend test's precondition: nothing may apply the first amend before the second is
     * sent. With settlement running the order stops being "pending" within milliseconds, and
     * the second amend is rightly accepted -- the product is correct and the test would fail.
     */
    private static void assumeNothingSettles() {
        try (org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(
                java.util.Map.<String, Object>of("bootstrap.servers", "localhost:9092"))) {
            var settlement = admin.describeConsumerGroups(java.util.List.of("spot-settlement")).all().get()
                    .get("spot-settlement");
            Assumptions.assumeTrue(settlement == null || settlement.members().isEmpty(),
                    "spot-settlement is running and would apply the first amend at once -- "
                            + "run `docker compose stop spot-settlement spot-matching-engine` to run this test");
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("could not ask the broker who is consuming", e);
        }
    }

    @Test
    @DisplayName("amend is queued with 202 and marks the order; a second one, a no-op and a bad field are refused")
    void amend() {
        assumeNothingSettles();
        // HttpURLConnection, TestRestTemplate's default, cannot send PATCH.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String clientId = "it-amend-" + UUID.randomUUID();
        String body = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\","
                + "\"price\":\"1000.00\",\"quantity\":\"0.02\",\"clientOrderId\":\"" + clientId + "\"}";
        String orderId = call(key, HttpMethod.POST, "/v1/orders", body).getBody().get("data").get("orderId").asText();

        assertThat(call(key, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"price\":\"1000.00\"}")
                .getBody().get("code").asInt()).isEqualTo(5027);
        assertThat(call(key, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"stopPrice\":\"900.00\"}")
                .getBody().get("code").asInt()).isEqualTo(5028);
        assertThat(call(key, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"price\":\"1000.005\"}")
                .getBody().get("code").asInt()).isEqualTo(5006);
        assertThat(call(readOnlyKey, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"price\":\"1001.00\"}")
                .getBody().get("code").asInt()).isEqualTo(2105);

        ResponseEntity<JsonNode> amended = call(key, HttpMethod.PATCH, "/v1/orders/by-client-id/" + clientId,
                "{\"price\":\"1001.00\",\"quantity\":\"0.03\"}");
        assertThat(amended.getStatusCode().value()).as(amended.getBody().toString()).isEqualTo(202);
        assertThat(amended.getBody().get("data").get("status").asText()).isEqualTo("AMENDING");
        JsonNode row = call(key, HttpMethod.GET, "/v1/orders/" + orderId, "").getBody().get("data");
        assertThat(row.get("amendPending").asBoolean()).isTrue();
        assertThat(row.get("price").asText()).as("the row changes only when settlement hears back").isEqualTo("1000");

        ResponseEntity<JsonNode> second = call(key, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"price\":\"1002.00\"}");
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().get("code").asInt()).isEqualTo(5026);

        call(key, HttpMethod.DELETE, "/v1/orders/" + orderId, "");
    }

    @Test
    @DisplayName("with no engine behind the market, placing is 5031 and the listing says paused; cancelling goes through")
    void pausedMarket() {
        String body = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        String orderId = call(key, HttpMethod.POST, "/v1/orders", body).getBody().get("data").get("orderId").asText();

        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        TestEnginePresence.live = false;
        try {
            ResponseEntity<JsonNode> refused = call(key, HttpMethod.POST, "/v1/orders", body);
            assertThat(refused.getStatusCode().value()).isEqualTo(503);
            assertThat(refused.getBody().get("code").asInt()).isEqualTo(5031);
            assertThat(call(key, HttpMethod.PATCH, "/v1/orders/" + orderId, "{\"price\":\"1001.00\"}")
                    .getBody().get("code").asInt()).isEqualTo(5031);

            catalog.refresh();   // the listing is a cache on a timer; the market was made moments ago
            JsonNode listed = rest.getForEntity("http://localhost:" + port + "/v1/markets/info", JsonNode.class)
                    .getBody().get("data").get("symbols");
            JsonNode mine = null;
            for (JsonNode m : listed) {
                if (m.get("symbol").asText().equals(symbol)) mine = m;
            }
            assertThat(mine).isNotNull();
            assertThat(mine.get("paused").asBoolean()).isTrue();

            assertThat(call(key, HttpMethod.DELETE, "/v1/orders/" + orderId, "").getStatusCode().value()).isEqualTo(202);
        } finally {
            TestEnginePresence.live = true;
        }
    }

    @Test
    @DisplayName("the domain's refusals arrive with this app's codes; a read-only key cannot place at all")
    void refusals() {
        String offStep = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.000001\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", offStep).getBody().get("code").asInt()).isEqualTo(5008);
        String marketBuyByQuantity = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"MARKET\",\"quantity\":\"0.01\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", marketBuyByQuantity).getBody().get("code").asInt()).isEqualTo(5009);
        assertThat(call(key, HttpMethod.GET, "/v1/orders/" + UUID.randomUUID(), "").getBody().get("code").asInt()).isEqualTo(5015);
        assertThat(call(key, HttpMethod.DELETE, "/v1/orders/by-client-id/never-" + UUID.randomUUID(), "").getBody().get("code").asInt()).isEqualTo(5015);
        assertThat(call(key, HttpMethod.GET, "/v1/orders?startTime=2020-01-01T00:00:00Z&endTime=2021-01-01T00:00:00Z", "")
                .getBody().get("code").asInt()).isEqualTo(5017);
        String fokExpireBoth = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"FOK\",\"price\":\"1000.00\",\"quantity\":\"0.02\",\"selfTradePreventionMode\":\"EXPIRE_BOTH\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", fokExpireBoth).getBody().get("code").asInt()).isEqualTo(5020);
        // Each enum field's bad value has its own code, not a generic parse error.
        String badSide = "{\"symbol\":\"" + symbol + "\",\"side\":\"HOLD\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", badSide).getBody().get("code").asInt()).isEqualTo(5021);
        String badType = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"ICEBERG\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", badType).getBody().get("code").asInt()).isEqualTo(5022);
        String badTif = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"DAY\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", badTif).getBody().get("code").asInt()).isEqualTo(5023);
        String none = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\",\"selfTradePreventionMode\":\"NONE\"}";
        assertThat(call(key, HttpMethod.POST, "/v1/orders", none).getBody().get("code").asInt()).isEqualTo(5024);
        assertThat(call(key, HttpMethod.POST, "/v1/orders", "{\"symbol\":").getBody().get("code").asInt()).isEqualTo(1000);
        String fine = "{\"symbol\":\"" + symbol + "\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        assertThat(call(readOnlyKey, HttpMethod.POST, "/v1/orders", fine).getBody().get("code").asInt()).isEqualTo(2105);
        assertThat(call(readOnlyKey, HttpMethod.GET, "/v1/orders/open", "").getStatusCode().value()).isEqualTo(200);
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
