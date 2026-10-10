package com.exchange.admin.bff.treasury;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.exchange.common.id.Ulid;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.treasury.FeeSweepService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The treasury screen's numbers, over HTTP (docs/reference/treasury.md). What it must get
 * right: the exchange's own accounts are reported apart from what customers hold, never summed
 * into it, and nothing is valued in anything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class TreasuryIT {

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeAll
    static void requireInfrastructure() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3308), 500);
        } catch (Exception e) {
            Assumptions.abort("port 3308 not reachable -- run `docker compose up -d`");
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AdminUserRepository admins;
    @Autowired
    private PasswordEncoder passwords;
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AssetRepository assets;
    @Autowired
    private FeeSweepService sweeps;

    private String secret;
    private Long treasuryAccountId;
    private Long customerId;
    private String customerEmail;

    private String email;
    private Long adminId;
    private final long customer = System.nanoTime();
    // An asset of the test's own: USDT carries whatever a developer has traded, and a running
    // settlement app sweeps its fees to the fee account every minute.
    private final String symbol = "TR" + customer % 1_000_000_000L;
    private Long assetId;
    private Long feeAccountId;

    @BeforeEach
    void seed() {
        email = "treasury-it-" + UUID.randomUUID().toString().substring(0, 8) + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.VIEWER)).getId();
        assetId = assets.save(new Asset(symbol, "treasury test asset", AssetType.CRYPTO, 8)).getId();
        feeAccountId = jdbc.queryForObject(
                "SELECT id FROM user_users WHERE email = 'fees@exchange'", Long.class);
        treasuryAccountId = jdbc.queryForObject(
                "SELECT id FROM user_users WHERE email = 'treasury@exchange'", Long.class);

    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        // By asset, never by account: the fee account is the real one, and its rows are not the test's.
        jdbc.update("DELETE FROM wallet_ledger_entries WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM wallet_balances WHERE asset_id = ?", assetId);
        jdbc.update("DELETE FROM wallet_assets WHERE id = ?", assetId);
        if (customerId != null) {
            jdbc.update("DELETE FROM user_users WHERE id = ?", customerId);
        }
    }

    @Test
    @DisplayName("reports what customers hold apart from the exchange's own accounts, in coins")
    void theHouseIsNotCountedAsCustomers() {
        String session = signIn();
        // Nobody holds it yet, so it is not on a list of what is held.
        assertThat(call(session, "/admin/treasury").getBody().get("data").get("assets"))
                .noneMatch(line -> line.get("symbol").asText().equals(symbol));

        balance(customer, 500_00000000L, 100_00000000L);
        balance(feeAccountId, 7_00000000L, 0L);
        fee(3_00000000L);

        JsonNode after = lineFor(call(session, "/admin/treasury").getBody().get("data"), symbol);

        // The customer's 600 is theirs; the fee account's 7 is ours, and is not added to it.
        assertThat(amount(after, "customerAvailable")).isEqualByComparingTo("500");
        assertThat(amount(after, "customerLocked")).isEqualByComparingTo("100");
        assertThat(amount(after, "customerTotal")).isEqualByComparingTo("600");
        assertThat(amount(after, "feeAccount")).isEqualByComparingTo("7");
        assertThat(after.get("holders").asLong()).as("the house is not a holder either").isEqualTo(1);

        // Coins, not a valuation: nothing here converts one asset into another.
        assertThat(after.toString()).doesNotContain("value").doesNotContain("quote");

        // What trading earned, read from the ledger rather than from the summaries.
        assertThat(fees(after, "today")).isEqualByComparingTo("3");
        assertThat(fees(after, "all")).isEqualByComparingTo("3");

        // The asset's switches are not the treasury's to show: two of the three act on nothing
        // until custody exists, and a control that is not a control misleads whoever reads it.
        assertThat(after.has("tradingEnabled")).isFalse();
        assertThat(after.has("depositEnabled")).isFalse();
    }

    private static BigDecimal amount(JsonNode line, String field) {
        return new BigDecimal(line.get(field).asText());
    }

    private static BigDecimal fees(JsonNode line, String period) {
        return new BigDecimal(line.get("periods").get(period).get("fees").asText());
    }

    @Test
    @DisplayName("the daily series covers every day asked for, a quiet day as a zero rather than a gap")
    void theSeriesHasNoGaps() {
        String session = signIn();
        fee(3_00000000L);

        JsonNode days = call(session, "/admin/treasury/revenue?asset=" + symbol + "&days=7").getBody().get("data");
        assertThat(days).hasSize(7);
        JsonNode today = days.get(6);
        assertThat(today.get("day").asText()).isEqualTo(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        assertThat(new BigDecimal(today.get("fees").asText())).isEqualByComparingTo("3");
        assertThat(days.get(0).get("fees").asText()).isNotNull();

        // An asset that does not exist is this app's own code, not a 500.
        ResponseEntity<JsonNode> unknown = call(session, "/admin/treasury/revenue?asset=NOPE");
        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().get("code").asInt()).isEqualTo(4002);
    }

    @Test
    @DisplayName("fees move to the treasury once, and the sweep does not sweep them into the fee account again")
    void feesMoveOnOnce() {
        fee(3_00000000L);
        // The running settlement app sweeps every minute too; either way, it lands once.
        sweeps.sweep(feeAccountId, assetId, Instant.now());
        assertThat(held(feeAccountId)).isEqualTo(3_00000000L);
        String session = operator();
        Map<String, Object> move = Map.of("id", UUID.randomUUID(), "asset", symbol, "amount", "3", "reason", "monthly");

        assertThat(post(session, "/admin/treasury/fee-moves", move, code(0)).getStatusCode().value()).isEqualTo(200);
        assertThat(held(feeAccountId)).isZero();
        assertThat(held(treasuryAccountId)).isEqualTo(3_00000000L);

        // The same request again, a double click or a retry: the same move, not a second one.
        assertThat(post(session, "/admin/treasury/fee-moves", move, code(30)).getStatusCode().value()).isEqualTo(200);
        assertThat(held(treasuryAccountId)).isEqualTo(3_00000000L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE admin_id = ? AND action = 'TREASURY_FEES_MOVED'",
                Long.class, adminId)).isEqualTo(1L);

        // The fee account is empty, but everything earned was swept: nothing to sweep again.
        assertThat(sweeps.sweep(feeAccountId, assetId, Instant.now())).isZero();
        assertThat(held(feeAccountId)).isZero();
    }

    @Test
    @DisplayName("a payout goes from the treasury to a customer's main account, never more than the treasury holds")
    void payout() {
        customer("PRIMARY");
        balance(treasuryAccountId, 10_00000000L, 0L);
        String session = operator();

        ResponseEntity<JsonNode> paid = post(session, "/admin/treasury/payouts", Map.of("id", UUID.randomUUID(),
                "customer", customerEmail, "asset", symbol, "amount", "4", "reason", "outage credit"), code(0));
        assertThat(paid.getStatusCode().value()).isEqualTo(200);
        assertThat(held(customerId)).isEqualTo(4_00000000L);
        assertThat(held(treasuryAccountId)).isEqualTo(6_00000000L);

        ResponseEntity<JsonNode> tooMuch = post(session, "/admin/treasury/payouts", Map.of("id", UUID.randomUUID(),
                "customer", customerId.toString(), "asset", symbol, "amount", "100", "reason", "x"), code(30));
        assertThat(tooMuch.getBody().get("code").asInt()).isEqualTo(4001);
        assertThat(held(treasuryAccountId)).isEqualTo(6_00000000L);

        JsonNode latest = call(session, "/admin/treasury/moves").getBody().get("data").get(0);
        assertThat(latest.get("kind").asText()).isEqualTo("PAYOUT");
        assertThat(latest.get("to").asText()).isEqualTo(customerEmail);
        assertThat(new BigDecimal(latest.get("amount").asText())).isEqualByComparingTo("4");
        assertThat(latest.get("reason").asText()).isEqualTo("outage credit");
        assertThat(latest.get("admin").asText()).isEqualTo(email);
    }

    @Test
    @DisplayName("a payout never goes to a sub-account or the house, and a VIEWER moves nothing")
    void refusals() {
        customer("SUB");
        balance(treasuryAccountId, 10_00000000L, 0L);

        String viewer = signIn();
        ResponseEntity<JsonNode> notAllowed = post(viewer, "/admin/treasury/fee-moves",
                Map.of("id", UUID.randomUUID(), "asset", symbol, "amount", "1", "reason", "x"), code(0));
        assertThat(notAllowed.getBody().get("code").asInt()).isEqualTo(2020);

        jdbc.update("UPDATE admin_users SET role = 'OPERATOR' WHERE id = ?", adminId);
        ResponseEntity<JsonNode> toSub = post(viewer, "/admin/treasury/payouts", Map.of("id", UUID.randomUUID(),
                "customer", customerId.toString(), "asset", symbol, "amount", "1", "reason", "x"), code(0));
        assertThat(toSub.getBody().get("code").asInt()).isEqualTo(2033);
        ResponseEntity<JsonNode> toHouse = post(viewer, "/admin/treasury/payouts", Map.of("id", UUID.randomUUID(),
                "customer", "fees@exchange", "asset", symbol, "amount", "1", "reason", "x"), code(30));
        assertThat(toHouse.getBody().get("code").asInt()).isEqualTo(2033);
        assertThat(held(treasuryAccountId)).isEqualTo(10_00000000L);
    }

    @Test
    @DisplayName("a request without a session is refused, not answered with the balance sheet")
    void staffOnly() {
        ResponseEntity<JsonNode> anonymous = call(null, "/admin/treasury");
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.getBody().get("code").asInt()).isEqualTo(2004);
    }

    /** An OPERATOR: what moving money needs. */
    private String operator() {
        jdbc.update("UPDATE admin_users SET role = 'OPERATOR' WHERE id = ?", adminId);
        return signIn();
    }

    /** A fresh code {@code seconds} from now: the login spent the step before, leaving now and the next. */
    private String code(int seconds) {
        return Totp.codeAt(secret, Instant.now().plusSeconds(seconds));
    }

    private void customer(String type) {
        customerEmail = "treasury-customer-" + UUID.randomUUID().toString().substring(0, 8) + "@x.dev";
        customerId = customer + 1;   // ids are the application's, not the table's (V14)
        jdbc.update("INSERT INTO user_users (id, email, type, status, email_verified) VALUES (?, ?, ?, 'ACTIVE', TRUE)",
                customerId, customerEmail, type);
    }

    private long held(long userId) {
        return jdbc.queryForList("SELECT available FROM wallet_balances WHERE user_id = ? AND asset_id = ?",
                Long.class, userId, assetId).stream().findFirst().orElse(0L);
    }

    /** Password, enrol, code: a VIEWER, since reading the balance sheet needs no more. */
    private String signIn() {
        ResponseEntity<JsonNode> login = post(null, "/admin/auth/login",
                Map.of("email", email, "password", PASSWORD));
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = post(cookie, "/admin/auth/totp/enrol", Map.of())
                .getBody().get("data").get("provisioningUri").asText();
        secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        post(cookie, "/admin/auth/totp/verify", Map.of("code", Totp.codeAt(secret, Instant.now().minusSeconds(30))));
        return cookie;
    }

    private static JsonNode lineFor(JsonNode data, String symbol) {
        for (JsonNode line : data.get("assets")) {
            if (line.get("symbol").asText().equals(symbol)) {
                return line;
            }
        }
        throw new AssertionError("no line for " + symbol);
    }

    private void balance(long userId, long available, long locked) {
        jdbc.update("INSERT INTO wallet_balances (user_id, asset_id, available, locked, version) "
                + "VALUES (?, ?, ?, ?, 0)", userId, assetId, available, locked);
    }

    /** A fee as settlement writes one: a debit on the trader, so a negative amount. */
    private void fee(long amount) {
        jdbc.update("INSERT INTO wallet_ledger_entries (id, user_id, asset_id, entry_type, amount, "
                        + "balance_after, reference_type, reference_id, idempotency_key, created_at) "
                        + "VALUES (?, ?, ?, 'FEE', ?, 0, 'TRADE', 'treasury-it', ?, NOW(6))",
                uuidBytes(Ulid.randomUuid()), customer, assetId, -amount,
                "treasury-it:" + UUID.randomUUID());
    }

    private static byte[] uuidBytes(UUID id) {
        byte[] bytes = new byte[16];
        long most = id.getMostSignificantBits();
        long least = id.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) (most >>> (8 * (7 - i)));
            bytes[8 + i] = (byte) (least >>> (8 * (7 - i)));
        }
        return bytes;
    }

    private ResponseEntity<JsonNode> call(String cookie, String path) {
        return exchange(cookie, HttpMethod.GET, path, null);
    }

    private ResponseEntity<JsonNode> post(String cookie, String path, Object body) {
        return exchange(cookie, HttpMethod.POST, path, body, null);
    }

    private ResponseEntity<JsonNode> post(String cookie, String path, Object body, String totp) {
        return exchange(cookie, HttpMethod.POST, path, body, totp);
    }

    private ResponseEntity<JsonNode> exchange(String cookie, HttpMethod method, String path, Object body) {
        return exchange(cookie, method, path, body, null);
    }

    private ResponseEntity<JsonNode> exchange(String cookie, HttpMethod method, String path, Object body, String totp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        if (totp != null) {
            headers.add("X-Admin-TOTP", totp);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
