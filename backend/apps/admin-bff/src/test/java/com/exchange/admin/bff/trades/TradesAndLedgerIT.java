package com.exchange.admin.bff.trades;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.bff.testing.Uid;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.exchange.common.id.Ulid;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
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
 * Trades and ledger lines as staff search them. Everything seeded lives on one day in 2001, so a
 * date window isolates it from whatever else the database holds, and ids are minted for that
 * day as the engine and the ledger mint theirs: the time first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class TradesAndLedgerIT {

    private static final String PASSWORD = "correct-horse-battery";
    private static final Instant DAY = Instant.parse("2001-02-03T00:00:00Z");
    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeAll
    static void requireInfrastructure() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3308), 500);
        } catch (Exception e) {
            Assumptions.abort("MySQL not reachable -- run `docker compose up -d`");
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

    private final String tag = "tl-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<byte[]> trades = new ArrayList<>();
    private final List<byte[]> entries = new ArrayList<>();
    private Long adminId;
    private long ann;
    private long bob;
    private long cat;
    private long symbolId;
    private String symbol;
    private long base;
    private long quote;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(tag + "@x.dev", passwords.encode(PASSWORD), AdminRole.VIEWER)).getId();
        ann = customer("ann");
        bob = customer("bob");
        cat = customer("cat");
        Map<String, Object> market = jdbc.queryForMap("SELECT id, symbol, base_asset_id, quote_asset_id FROM spot_symbols ORDER BY id LIMIT 1");
        symbolId = ((Number) market.get("id")).longValue();
        symbol = (String) market.get("symbol");
        base = ((Number) market.get("base_asset_id")).longValue();
        quote = ((Number) market.get("quote_asset_id")).longValue();

        trade(DAY.plusSeconds(60), ann, bob, 100_000L);     // ann buys from bob, paying a fee in base
        trade(DAY.plusSeconds(120), bob, cat, 0L);          // not ann's
        trade(DAY.plusSeconds(180), cat, ann, 0L);          // ann sells to cat

        entry(DAY.plusSeconds(10), ann, quote, "DEPOSIT", 100_00000000L, 100_00000000L);
        entry(DAY.plusSeconds(60), ann, base, "TRADE_CREDIT", 1_00000000L, 1_00000000L);
        entry(DAY.plusSeconds(61), ann, base, "FEE", -100_000L, 99_900_000L);
    }

    @AfterEach
    void cleanUp() {
        trades.forEach(id -> jdbc.update("DELETE FROM spot_trades WHERE id = ?", (Object) id));
        entries.forEach(id -> jdbc.update("DELETE FROM wallet_ledger_entries WHERE id = ?", (Object) id));
        jdbc.update("DELETE FROM user_users WHERE email LIKE ?", tag + "-%");
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("a customer's trades are both sides, merged newest first, and page without a total")
    void customersTrades() {
        String session = signIn();

        JsonNode all = get(session, "/admin/trades?customer=" + tag + "-ann@x.dev");
        assertThat(sides(all, "seller")).containsExactly(ann, bob);
        JsonNode bought = all.get("items").get(1);
        assertThat(bought.get("buyer").get("email").asText()).isEqualTo(tag + "-ann@x.dev");
        assertThat(bought.get("buyer").get("fee").asText()).isEqualTo("0.001");
        assertThat(bought.get("buyer").get("feeAsset").isNull()).isFalse();
        assertThat(bought.get("seller").get("feeAsset").isNull()).isTrue();
        assertThat(all.get("more").asBoolean()).isFalse();

        JsonNode first = get(session, "/admin/trades?customer=" + ann + "&size=1");
        assertThat(sides(first, "seller")).containsExactly(ann);
        assertThat(first.get("more").asBoolean()).isTrue();
        JsonNode second = get(session, "/admin/trades?customer=" + ann + "&size=1&page=1");
        assertThat(sides(second, "seller")).containsExactly(bob);
        assertThat(second.get("more").asBoolean()).isFalse();

        assertThat(get(session, "/admin/trades?customer=nobody-" + tag + "@x.dev").get("items")).isEmpty();
    }

    @Test
    @DisplayName("everyone's trades in a window, newest first by id")
    void everyonesTrades() {
        String session = signIn();
        JsonNode day = get(session, "/admin/trades?symbol=" + symbol + "&from=" + DAY + "&to=" + DAY.plusSeconds(86_400));
        assertThat(sides(day, "buyer")).containsExactly(cat, bob, ann);
        assertThat(day.get("items").get(0).get("symbol").asText()).isEqualTo(symbol);

        JsonNode late = get(session, "/admin/trades?from=" + DAY.plusSeconds(120) + "&to=" + DAY.plusSeconds(180));
        assertThat(sides(late, "buyer")).containsExactly(bob);
    }

    @Test
    @DisplayName("a customer's ledger across assets, filtered by asset and type; everyone's by window")
    void ledger() {
        String session = signIn();

        JsonNode statement = get(session, "/admin/ledger?customer=" + ann);
        assertThat(types(statement)).containsExactly("FEE", "TRADE_CREDIT", "DEPOSIT");
        JsonNode fee = statement.get("items").get(0);
        assertThat(fee.get("amount").asText()).isEqualTo("-0.001");
        assertThat(fee.get("balanceAfter").asText()).isEqualTo("0.999");
        assertThat(fee.get("userEmail").asText()).isEqualTo(tag + "-ann@x.dev");

        String quoteSymbol = jdbc.queryForObject("SELECT symbol FROM wallet_assets WHERE id = ?", String.class, quote);
        assertThat(types(get(session, "/admin/ledger?customer=" + ann + "&asset=" + quoteSymbol))).containsExactly("DEPOSIT");
        assertThat(types(get(session, "/admin/ledger?customer=" + ann + "&type=FEE"))).containsExactly("FEE");

        String window = "&from=" + DAY + "&to=" + DAY.plusSeconds(86_400);
        assertThat(types(get(session, "/admin/ledger?type=TRADE_CREDIT" + window))).containsExactly("TRADE_CREDIT");
        assertThat(types(get(session, "/admin/ledger?size=2" + window))).containsExactly("FEE", "TRADE_CREDIT");

        assertThat(get(session, "/admin/ledger?asset=NOSUCHCOIN").get("items")).isEmpty();
        assertThat(call(session, "/admin/ledger?type=GIFT").getStatusCode().value()).isEqualTo(400);
    }

    private long customer(String name) {
        jdbc.update("INSERT INTO user_users (id, email, email_verified, status, type, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, 'ACTIVE', 'PRIMARY', 'BASIC', TRUE)", Uid.next(), tag + "-" + name + "@x.dev");
        return jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, tag + "-" + name + "@x.dev");
    }

    private void trade(Instant at, long buyer, long seller, long buyerFee) {
        byte[] id = idAt(at);
        trades.add(id);
        jdbc.update("INSERT INTO spot_trades (id, symbol_id, price, quantity, quote_quantity, buyer_order_id, seller_order_id, "
                + "buyer_user_id, seller_user_id, buyer_is_maker, buyer_fee, buyer_fee_asset_id, seller_fee, sequence, executed_at) "
                + "VALUES (?, ?, 100000000, 100000000, 100000000, ?, ?, ?, ?, FALSE, ?, ?, 0, ?, ?)",
                id, symbolId, idAt(at), idAt(at), buyer, seller, buyerFee, buyerFee == 0 ? null : base,
                Long.MAX_VALUE - (RANDOM.nextLong() & 0xFFFFFFFFL), Timestamp.from(at));
    }

    private void entry(Instant at, long userId, long assetId, String type, long amount, long after) {
        byte[] id = idAt(at);
        entries.add(id);
        jdbc.update("INSERT INTO wallet_ledger_entries (id, user_id, asset_id, entry_type, amount, balance_after, "
                + "idempotency_key, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, userId, assetId, type, amount, after, tag + "-" + UUID.randomUUID(), Timestamp.from(at));
    }

    /** A ULID minted at {@code at}. */
    private static byte[] idAt(Instant at) {
        UUID floor = Ulid.floor(at);
        return ByteBuffer.allocate(16).putLong(floor.getMostSignificantBits() | (RANDOM.nextInt() & 0xFFFF))
                .putLong(RANDOM.nextLong()).array();
    }

    private static List<Long> sides(JsonNode chunk, String side) {
        return StreamSupport.stream(chunk.get("items").spliterator(), false)
                .map(r -> r.get(side).get("userId").asLong()).toList();
    }

    private static List<String> types(JsonNode chunk) {
        return StreamSupport.stream(chunk.get("items").spliterator(), false).map(r -> r.get("type").asText()).toList();
    }

    private JsonNode get(String session, String path) {
        return call(session, path).getBody().get("data");
    }

    private ResponseEntity<JsonNode> call(String session, String path) {
        return exchange(session, HttpMethod.GET, path, null);
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = exchange(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", tag + "@x.dev", "password", PASSWORD));
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = exchange(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of()).getBody().get("data").get("provisioningUri").asText();
        String secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        exchange(cookie, HttpMethod.POST, "/admin/auth/totp/verify", Map.of("code", Totp.codeAt(secret, Instant.now())));
        return cookie;
    }

    private ResponseEntity<JsonNode> exchange(String cookie, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) headers.add(HttpHeaders.COOKIE, cookie);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
