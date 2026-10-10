package com.exchange.bff.copy;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.common.id.Ulid;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.user.mfa.MfaService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A leader trading the lead account by hand and by key, over real HTTP and the real tables
 * (docs/reference/copy-trading.md). What only this can show: the account a request names is
 * the one its order, its cancel and its balances belong to, the order goes through the hook
 * that makes it copyable, and nobody can name an account that is not theirs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class LeadTradingIT {

    private static final long ONE = 100_000_000L;
    private static final String PASSWORD = "correct-horse-battery";
    private static final String LEAD = "lead";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private KycService kyc;
    @Autowired private MfaService mfa;
    @Autowired private CopyLeaderService leaderService;
    @Autowired private BalanceService balanceService;
    @Autowired private AssetRepository assets;
    @Autowired private SpotSymbolRepository markets;
    @Autowired private JdbcTemplate jdbc;

    private final String suffix = Long.toString(System.nanoTime() % 1_000_000_000L);
    private final List<Long> accounts = new ArrayList<>();
    private SpotSymbol market;
    private Long usdt;
    private String totpSecret;

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092, 27018}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("infrastructure not reachable on " + port + " -- run `docker compose up -d`");
            }
        }
    }

    @BeforeEach
    void aMarketOfItsOwn() {
        // A lead account trades the copy asset's markets only, so the quote is the real USDT;
        // the base is this test's, so no order here can meet anybody else's.
        usdt = assets.findBySymbol("USDT").orElseThrow().getId();
        Long base = assets.save(new Asset("LT" + suffix, "lead trading test base", AssetType.CRYPTO, 8)).getId();
        SpotSymbol made = new SpotSymbol("LT" + suffix + "USDT", base, usdt, ONE, ONE, ONE, 1_000_000 * ONE, ONE, 0);
        made.setStatus(SymbolStatus.TRADING);
        market = markets.save(made);
    }

    @AfterEach
    void removeWhatWasMade() {
        for (Long id : accounts) {
            jdbc.update("DELETE o FROM copy_lead_orders o JOIN copy_leaders l ON l.id = o.leader_id WHERE l.user_id = ?", id);
            jdbc.update("DELETE FROM copy_leaders WHERE user_id = ?", id);
        }
        jdbc.update("DELETE FROM spot_command_outbox WHERE symbol = ?", market.getSymbol());
        jdbc.update("DELETE FROM spot_orders WHERE symbol_id = ?", market.getId());
        new SeededDataCleaner(jdbc).purgeUsers(accounts);
        jdbc.update("DELETE FROM spot_symbols WHERE id = ?", market.getId());
        jdbc.update("DELETE FROM wallet_assets WHERE id = ?", market.getBaseAssetId());
    }

    @Test
    @DisplayName("a request that names the lead account places, lists and cancels that account's orders and reads "
            + "its balances; one that does not is the customer's own, and neither sees the other's")
    void theAccountNamedIsTheOneTraded() {
        HttpHeaders session = customer();
        long me = userId(session);
        long lead = becomeLeader(session);
        // 500 went in with the application; the rest makes the round numbers below.
        balanceService.credit(me, usdt, 1_000 * ONE, ReferenceType.DEPOSIT, "lead-it", "lead-it:own:" + suffix);
        balanceService.credit(lead, usdt, 1_500 * ONE, ReferenceType.DEPOSIT, "lead-it", "lead-it:lead:" + suffix);
        Map<String, String> buy = Map.of("symbol", market.getSymbol(), "side", "BUY", "orderType", "LIMIT",
                "timeInForce", "GTC", "price", "100", "quantity", "5");

        ResponseEntity<JsonNode> asLead = send(session, LEAD, HttpMethod.POST, "/bff/orders", buy);
        assertThat(asLead.getStatusCode().value()).as(String.valueOf(asLead.getBody())).isEqualTo(202);
        String leadOrder = asLead.getBody().get("data").get("orderId").asText();
        String ownOrder = send(session, null, HttpMethod.POST, "/bff/orders", buy).getBody().get("data").get("orderId").asText();

        assertThat(owner(leadOrder)).isEqualTo(lead);
        assertThat(owner(ownOrder)).isEqualTo(me);
        // Through the hook: a quarter of the lead account's 2,000 USDT, written for the copier.
        assertThat(jdbc.queryForObject("SELECT fraction FROM copy_lead_orders WHERE order_id = ?", Long.class,
                (Object) Ulid.toBytes(UUID.fromString(leadOrder)))).isEqualTo(ONE / 4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM copy_lead_orders WHERE order_id = ?", Long.class,
                (Object) Ulid.toBytes(UUID.fromString(ownOrder)))).as("the customer's own order is nobody's to copy").isZero();

        assertThat(ids(send(session, LEAD, HttpMethod.GET, "/bff/orders", null))).containsExactly(leadOrder);
        assertThat(ids(send(session, null, HttpMethod.GET, "/bff/orders", null))).containsExactly(ownOrder);
        assertThat(ids(send(session, LEAD, HttpMethod.GET, "/bff/orders/history", null))).containsExactly(leadOrder);
        assertThat(locked(send(session, LEAD, HttpMethod.GET, "/bff/wallet/balances", null))).isEqualTo("500");
        assertThat(available(send(session, LEAD, HttpMethod.GET, "/bff/wallet/balances", null))).isEqualTo("1500");
        assertThat(available(send(session, null, HttpMethod.GET, "/bff/wallet/balances", null))).isEqualTo("500");

        // A leader with an order open cannot stop leading: the account must be flat first.
        assertThat(send(session, null, HttpMethod.POST, "/bff/copy/lead/close", Map.of()).getBody().get("code").asInt())
                .isEqualTo(8016);

        // An order is cancelled as the account that placed it, and as no other.
        assertThat(send(session, null, HttpMethod.DELETE, "/bff/orders/" + leadOrder, null).getStatusCode().value())
                .as("the customer's own account does not hold the lead account's order").isEqualTo(404);
        assertThat(send(session, LEAD, HttpMethod.DELETE, "/bff/orders/" + ownOrder, null).getStatusCode().value())
                .isEqualTo(404);
        assertThat(send(session, LEAD, HttpMethod.DELETE, "/bff/orders/" + leadOrder, null).getStatusCode().value())
                .isEqualTo(202);
        assertThat(send(session, null, HttpMethod.DELETE, "/bff/orders/" + ownOrder, null).getStatusCode().value())
                .isEqualTo(202);
    }

    @Test
    @DisplayName("a customer with no lead account cannot name one, and no other account can be named at all")
    void onlyALeadAccountOfOnesOwn() {
        HttpHeaders stranger = customer();
        Map<String, String> buy = Map.of("symbol", market.getSymbol(), "side", "BUY", "orderType", "LIMIT",
                "timeInForce", "GTC", "price", "100", "quantity", "5");

        assertThat(send(stranger, LEAD, HttpMethod.GET, "/bff/orders", null).getBody().get("code").asInt()).isEqualTo(8012);
        assertThat(send(stranger, LEAD, HttpMethod.POST, "/bff/orders", buy).getBody().get("code").asInt()).isEqualTo(8012);
        assertThat(send(stranger, LEAD, HttpMethod.GET, "/bff/wallet/balances", null).getBody().get("code").asInt())
                .isEqualTo(8012);
        assertThat(send(stranger, LEAD, HttpMethod.GET, "/bff/trades", null).getBody().get("code").asInt()).isEqualTo(8012);

        HttpHeaders leader = customer();
        long lead = becomeLeader(leader);
        // An id is not a name: the one account that can be named is found from the session.
        assertThat(send(stranger, String.valueOf(lead), HttpMethod.GET, "/bff/orders", null).getBody().get("code").asInt())
                .isEqualTo(8012);
        assertThat(send(leader, String.valueOf(userId(stranger)), HttpMethod.GET, "/bff/wallet/balances", null)
                .getBody().get("code").asInt()).isEqualTo(8012);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_orders WHERE symbol_id = ?", Long.class, market.getId()))
                .isZero();
    }

    @Test
    @DisplayName("a key for the lead account is minted with its owner's code, belongs to the lead account, never "
            + "withdraws, and is listed and revoked apart from the owner's own keys")
    void aKeyForTheLeadAccount() {
        HttpHeaders session = customer();
        Map<String, Object> key = Map.of("label", "bot", "canRead", true, "canTrade", true,
                "mfaCode", Totp.codeAt(totpSecret, Instant.now().plusSeconds(30)));
        assertThat(send(session, null, HttpMethod.POST, "/bff/copy/lead/api-keys", key).getBody().get("code").asInt())
                .as("no lead account yet").isEqualTo(8012);
        long lead = becomeLeader(session);

        Map<String, Object> wrong = Map.of("label", "bot", "canRead", true, "canTrade", true, "mfaCode", "000000");
        assertThat(send(session, null, HttpMethod.POST, "/bff/copy/lead/api-keys", wrong).getStatusCode().value())
                .isEqualTo(401);
        ResponseEntity<JsonNode> made = send(session, null, HttpMethod.POST, "/bff/copy/lead/api-keys", key);
        assertThat(made.getStatusCode().value()).as(String.valueOf(made.getBody())).isEqualTo(201);
        JsonNode created = made.getBody().get("data");
        assertThat(created.get("secret").asText()).isNotBlank();
        assertThat(created.get("canWithdraw").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT user_id FROM user_api_keys WHERE id = ?", Long.class,
                (Object) Ulid.toBytes(UUID.fromString(created.get("id").asText())))).isEqualTo(lead);

        JsonNode listed = send(session, null, HttpMethod.GET, "/bff/copy/lead/api-keys", null).getBody().get("data");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).toString()).doesNotContain("secret");
        assertThat(send(session, null, HttpMethod.GET, "/bff/api-keys", null).getBody().get("data"))
                .as("not one of the customer's own keys").isEmpty();

        String keyId = created.get("id").asText();
        assertThat(send(customer(), null, HttpMethod.DELETE, "/bff/copy/lead/api-keys/" + keyId, null).getBody()
                .get("code").asInt()).as("somebody else").isEqualTo(8012);
        assertThat(send(session, null, HttpMethod.DELETE, "/bff/copy/lead/api-keys/" + keyId, null).getStatusCode().value())
                .isEqualTo(204);
        assertThat(send(session, null, HttpMethod.GET, "/bff/copy/lead/api-keys", null).getBody().get("data").get(0)
                .get("revokedAt").isNull()).isFalse();
    }

    /** Becomes a leader in one step, moving 500 USDT of the customer's own in; returns the lead account. */
    private long becomeLeader(HttpHeaders session) {
        long me = userId(session);
        balanceService.credit(me, usdt, 500 * ONE, ReferenceType.DEPOSIT, "lead-it", "lead-it:seed:" + me);
        ResponseEntity<JsonNode> applied = send(session, null, HttpMethod.POST, "/bff/copy/lead/apply",
                Map.of("displayName", "Lead " + UUID.randomUUID().toString().substring(0, 13), "showPortfolio", true,
                        "transferId", UUID.randomUUID().toString(), "amount", "500"));
        assertThat(applied.getStatusCode().value()).as(String.valueOf(applied.getBody())).isEqualTo(201);
        assertThat(applied.getBody().get("data").get("status").asText()).isEqualTo("ACTIVE");
        Long lead = leaderService.mine(me).orElseThrow().leader().getLeadUserId();
        accounts.add(lead);
        return lead;
    }

    /** A customer cleared to trade and to mint keys, with a real second factor. */
    private HttpHeaders customer() {
        String email = "lead-trading-" + UUID.randomUUID() + "@x.dev";
        send(null, null, HttpMethod.POST, "/bff/auth/register", Map.of("email", email, "password", PASSWORD, "name", "IT"));
        ResponseEntity<JsonNode> loggedIn = send(null, null, HttpMethod.POST, "/bff/auth/login",
                Map.of("email", email, "password", PASSWORD));
        HttpHeaders session = new HttpHeaders();
        session.add(HttpHeaders.COOKIE, loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0]);
        long id = userId(session);
        accounts.add(id);
        User user = users.findById(id).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
        kyc.approve(id, KycLevel.INTERMEDIATE, null);
        totpSecret = mfa.startTotpEnrolment(id, email, "Exchange").secret();
        mfa.confirmTotpEnrolment(id, Totp.codeAt(totpSecret, Instant.now()), "127.0.0.1");
        return session;
    }

    private long userId(HttpHeaders session) {
        return send(session, null, HttpMethod.GET, "/bff/auth/me", null).getBody().get("data").get("id").asLong();
    }

    private long owner(String orderId) {
        return jdbc.queryForObject("SELECT user_id FROM spot_orders WHERE id = ?", Long.class,
                (Object) Ulid.toBytes(UUID.fromString(orderId)));
    }

    private static List<String> ids(ResponseEntity<JsonNode> orders) {
        List<String> ids = new ArrayList<>();
        orders.getBody().get("data").forEach(o -> ids.add(o.get("orderId").asText()));
        return ids;
    }

    private static String available(ResponseEntity<JsonNode> balances) {
        return usdtOf(balances).get("available").asText();
    }

    private static String locked(ResponseEntity<JsonNode> balances) {
        return usdtOf(balances).get("locked").asText();
    }

    private static JsonNode usdtOf(ResponseEntity<JsonNode> balances) {
        for (JsonNode balance : balances.getBody().get("data")) {
            if ("USDT".equals(balance.get("asset").asText())) {
                return balance;
            }
        }
        throw new AssertionError("no USDT balance in " + balances.getBody());
    }

    /** @param account the account the request names, or null for the customer's own */
    private ResponseEntity<JsonNode> send(HttpHeaders session, String account, HttpMethod method, String path,
                                          Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.addAll(session);
        }
        if (account != null) {
            headers.add("X-Trading-Account", account);
        }
        return rest.exchange("http://localhost:" + port + path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
