package com.exchange.admin.bff.markets;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.HashMap;
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
 * Listing and running a market over HTTP. Each test lists a pair of seeded assets that has no
 * market yet, and removes it after. A test has two authenticator codes -- the current step and the
 * next -- so each covers at most two writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class MarketsIT {

    private static final String PASSWORD = "correct-horse-battery";

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

    private final String email = "markets-it-" + UUID.randomUUID().toString().substring(0, 8) + "@x.dev";
    private Long adminId;
    private String secret;
    private long base;
    private long quote;
    private String symbol;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        // Two assets with no market between them, the base first: anything seeded that is not yet a pair.
        Map<String, Object> pair = jdbc.queryForMap("SELECT b.id AS base, q.id AS quote, CONCAT(b.symbol, q.symbol) AS symbol "
                + "FROM wallet_assets b JOIN wallet_assets q ON b.id <> q.id "
                + "WHERE NOT EXISTS (SELECT 1 FROM spot_symbols s WHERE (s.base_asset_id = b.id AND s.quote_asset_id = q.id) "
                + "   OR s.symbol = CONCAT(b.symbol, q.symbol)) AND CHAR_LENGTH(CONCAT(b.symbol, q.symbol)) <= 20 "
                + "ORDER BY b.id, q.id LIMIT 1");
        base = ((Number) pair.get("base")).longValue();
        quote = ((Number) pair.get("quote")).longValue();
        symbol = (String) pair.get("symbol");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM spot_symbols WHERE symbol = ?", symbol);
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("the page lists every market, the assets a new one is made of, and the engine's partitions")
    void lists() {
        JsonNode page = get(signIn(), "/admin/markets");
        assertThat(page.get("partitionCount").asInt()).isEqualTo(8);
        assertThat(page.get("assets").size()).isGreaterThan(1);
        JsonNode btc = null;
        for (JsonNode m : page.get("markets")) {
            if (m.get("symbol").asText().equals("BTCUSDT")) {
                btc = m;
            }
        }
        assertThat(btc).isNotNull();
        assertThat(btc.get("base").asText()).isEqualTo("BTC");
        assertThat(btc.get("limits").get("minNotional").asText()).isNotBlank();
    }

    @Test
    @DisplayName("a market is listed closed, with a code, and opened with another; both are audited")
    void listAndOpen() {
        String session = signIn();
        ResponseEntity<JsonNode> listed = call(session, HttpMethod.POST, "/admin/markets", newMarket(3), code(0));
        assertThat(listed.getStatusCode().value()).as(listed.getBody().toString()).isEqualTo(200);
        JsonNode row = listed.getBody().get("data");
        assertThat(row.get("symbol").asText()).isEqualTo(symbol);
        assertThat(row.get("status").asText()).isEqualTo("PRE_TRADING");
        assertThat(row.get("commandPartition").asInt()).isEqualTo(3);
        assertThat(row.get("priceTick").asText()).isEqualTo("0.01");

        JsonNode opened = call(session, HttpMethod.POST, "/admin/markets/" + row.get("id").asLong() + "/status",
                Map.of("status", "TRADING"), code(30)).getBody().get("data");
        assertThat(opened.get("status").asText()).isEqualTo("TRADING");

        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? AND target_type = 'market' ORDER BY id",
                String.class, adminId)).containsExactly("MARKET_LISTED", "MARKET_STATUS_CHANGED");
    }

    @Test
    @DisplayName("limits change and are checked against the step; the tick and step themselves never change")
    void limits() {
        long id = listDirectly();
        String session = signIn();

        Map<String, Object> limits = limitsBody("0.01");
        limits.put("priceBandBps", 500);
        limits.put("stopOrdersEnabled", true);
        JsonNode changed = call(session, HttpMethod.PATCH, "/admin/markets/" + id + "/limits", limits, code(0))
                .getBody().get("data");
        assertThat(changed.get("limits").get("minQty").asText()).isEqualTo("0.01");
        assertThat(changed.get("limits").get("priceBandBps").asInt()).isEqualTo(500);
        assertThat(changed.get("qtyStep").asText()).isEqualTo("0.001");
        assertThat(changed.get("priceTick").asText()).isEqualTo("0.01");

        // Off the step: no order could ever meet it.
        ResponseEntity<JsonNode> off = call(session, HttpMethod.PATCH, "/admin/markets/" + id + "/limits",
                limitsBody("0.0015"), code(30));
        assertThat(off.getBody().get("code").asInt()).isEqualTo(5031);
        assertThat(off.getBody().get("message").asText()).contains("multiple");
    }

    @Test
    @DisplayName("a new market is opened, never wound down before it trades; a listed pair is not listed twice")
    void statusesAndDuplicates() {
        long id = listDirectly();
        String session = signIn();

        assertThat(call(session, HttpMethod.POST, "/admin/markets/" + id + "/status", Map.of("status", "CANCEL_ONLY"),
                code(0)).getBody().get("code").asInt()).isEqualTo(5031);
        assertThat(call(session, HttpMethod.POST, "/admin/markets", newMarket(0), code(30))
                .getBody().get("code").asInt()).isEqualTo(5032);
    }

    @Test
    @DisplayName("a write needs a fresh code, a partition the engine has, and an OPERATOR")
    void guards() {
        String session = signIn();
        assertThat(call(session, HttpMethod.POST, "/admin/markets", newMarket(0), null)
                .getBody().get("code").asInt()).isEqualTo(2021);
        assertThat(call(session, HttpMethod.POST, "/admin/markets", newMarket(8), code(0))
                .getBody().get("code").asInt()).isEqualTo(5031);

        // The role is read on every request, so the same session is now a VIEWER's.
        jdbc.update("UPDATE admin_users SET role = 'VIEWER' WHERE id = ?", adminId);
        assertThat(get(session, "/admin/markets").get("markets").size()).isGreaterThan(0);
        assertThat(call(session, HttpMethod.POST, "/admin/markets", newMarket(0), code(30))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM spot_symbols WHERE symbol = ?", Integer.class, symbol)).isZero();
    }

    /** Listed without HTTP, so a test's two codes go to what it is about. */
    private long listDirectly() {
        jdbc.update("INSERT INTO spot_symbols (symbol, base_asset_id, quote_asset_id, command_partition, status, "
                + "price_tick, qty_step, min_qty, max_qty, min_notional) VALUES (?, ?, ?, 0, 'PRE_TRADING', "
                + "1000000, 100000, 100000, 100000000000, 100000000)", symbol, base, quote);
        return jdbc.queryForObject("SELECT id FROM spot_symbols WHERE symbol = ?", Long.class, symbol);
    }

    private Map<String, Object> newMarket(int partition) {
        return Map.of("baseAssetId", base, "quoteAssetId", quote, "commandPartition", partition,
                "priceTick", "0.01", "qtyStep", "0.001", "limits", limitsBody("0.001"));
    }

    private static Map<String, Object> limitsBody(String minQty) {
        Map<String, Object> limits = new HashMap<>(Map.of("minQty", minQty, "maxQty", "1000", "minNotional", "1",
                "maxOpenOrders", 200, "maxOpenConditionalOrders", 50, "marketOrdersEnabled", true,
                "stopOrdersEnabled", false));
        return limits;
    }

    /** The code for {@code seconds} from now: 0 and 30 are the two a test may spend. */
    private String code(int seconds) {
        return Totp.codeAt(secret, Instant.now().plusSeconds(seconds));
    }

    private JsonNode get(String session, String path) {
        return call(session, HttpMethod.GET, path, null, null).getBody().get("data");
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", email, "password", PASSWORD), null);
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = call(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of(), null).getBody().get("data").get("provisioningUri").asText();
        secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        // One step back for the login, leaving the current step and the next for two writes.
        call(cookie, HttpMethod.POST, "/admin/auth/totp/verify", Map.of("code", Totp.codeAt(secret, Instant.now().minusSeconds(30))), null);
        return cookie;
    }

    private ResponseEntity<JsonNode> call(String cookie, HttpMethod method, String path, Object body, String totp) {
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
