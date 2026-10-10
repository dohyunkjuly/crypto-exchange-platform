package com.exchange.admin.bff.overrides;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.bff.testing.Uid;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.HashMap;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A customer's fee and limit overrides over HTTP. A master and its sub are seeded per test; a test
 * has two authenticator codes, so at most two fee writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class UserOverridesIT {

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

    private final String tag = "overrides-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long adminId;
    private String secret;
    private long master;
    private long sub;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(tag + "@x.dev", passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        master = user(tag + "-m@x.dev", "PRIMARY", null);
        sub = user(tag + "-s_virtual@x.dev", "SUB", master);
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("fee_user_overrides", "spot_user_order_limit_overrides",
                "spot_symbol_user_order_limit_overrides")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id IN (?, ?)", master, sub);
        }
        jdbc.update("DELETE FROM user_users WHERE id = ?", sub);
        jdbc.update("DELETE FROM user_users WHERE id = ?", master);
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("a fee override is set on the master with a code, and its sub pays it too; a sub has none of its own to set")
    void feeOverrideCoversTheSub() {
        String session = signIn();
        JsonNode before = get(session, "/admin/users/" + sub + "/overrides").get("fee");
        assertThat(before.get("source").asText()).isEqualTo("TIER");
        assertThat(before.get("override").isNull()).isTrue();

        JsonNode set = call(session, HttpMethod.POST, "/admin/users/" + master + "/overrides/fee",
                Map.of("makerFeeBps", -1, "takerFeeBps", 4, "reason", "market maker agreement"), code(0))
                .getBody().get("data").get("fee");
        assertThat(set.get("source").asText()).isEqualTo("OVERRIDE");
        assertThat(set.get("override").get("grantedBy").asText()).isEqualTo(tag + "@x.dev");

        JsonNode inherited = get(session, "/admin/users/" + sub + "/overrides").get("fee");
        assertThat(inherited.get("source").asText()).isEqualTo("OVERRIDE");
        assertThat(inherited.get("override").get("reason").asText()).isEqualTo("market maker agreement");
        assertThat(inherited.get("makerFeeBps").asInt()).isEqualTo(-1);
        assertThat(inherited.get("takerFeeBps").asInt()).isEqualTo(4);
        assertThat(inherited.get("subAccount").asBoolean()).isTrue();

        assertThat(call(session, HttpMethod.POST, "/admin/users/" + sub + "/overrides/fee",
                Map.of("makerFeeBps", 0, "takerFeeBps", 1, "reason", "x"), code(30)).getBody().get("code").asInt())
                .isEqualTo(2030);
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? AND action LIKE 'USER_FEE%'",
                String.class, adminId)).containsExactly("USER_FEE_OVERRIDE_SET");
    }

    @Test
    @DisplayName("a fee needs a code; a rebate above the taker fee is refused; removing one needs a code too")
    void feeGuards() {
        String session = signIn();
        assertThat(call(session, HttpMethod.POST, "/admin/users/" + master + "/overrides/fee",
                Map.of("makerFeeBps", 0, "takerFeeBps", 1, "reason", "x"), null).getBody().get("code").asInt())
                .isEqualTo(2021);
        ResponseEntity<JsonNode> costly = call(session, HttpMethod.POST, "/admin/users/" + master + "/overrides/fee",
                Map.of("makerFeeBps", -5, "takerFeeBps", 4, "reason", "x"), code(0));
        assertThat(costly.getBody().get("code").asInt()).isEqualTo(2031);
        assertThat(costly.getBody().get("message").asText()).contains("rebate");
        assertThat(call(session, HttpMethod.DELETE, "/admin/users/" + master + "/overrides/fee", null, code(30))
                .getBody().get("code").asInt()).as("nothing to remove").isEqualTo(2032);
    }

    @Test
    @DisplayName("order limits are set and removed per account, against the defaults they replace, and audited")
    void limits() {
        String session = signIn();
        JsonNode account = call(session, HttpMethod.POST, "/admin/users/" + sub + "/overrides/order-limit",
                body(null, 5000, 300, "bot runs a grid"), null).getBody().get("data").get("orderLimit");
        assertThat(account.get("defaultOrders").asInt()).isPositive();
        assertThat(account.get("own").get("maxOrders").asInt()).isEqualTo(5000);
        assertThat(get(session, "/admin/users/" + master + "/overrides").get("orderLimit").get("own").isNull())
                .as("per account: the master is untouched").isTrue();

        JsonNode markets = call(session, HttpMethod.POST, "/admin/users/" + sub + "/overrides/markets",
                body("btcusdt", 800, null, "grid on BTC"), null).getBody().get("data").get("marketLimits");
        assertThat(markets.get(0).get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(markets.get(0).get("own").get("maxStops").isNull()).isTrue();

        assertThat(call(session, HttpMethod.POST, "/admin/users/" + sub + "/overrides/order-limit",
                body(null, 10, 20, "x"), null).getBody().get("code").asInt()).as("stops above orders").isEqualTo(2031);
        assertThat(call(session, HttpMethod.POST, "/admin/users/" + sub + "/overrides/markets",
                body("NOPE", 1, null, "x"), null).getBody().get("code").asInt()).isEqualTo(5001);

        JsonNode removed = call(session, HttpMethod.DELETE, "/admin/users/" + sub + "/overrides/markets/BTCUSDT", null,
                null).getBody().get("data");
        assertThat(removed.get("marketLimits")).isEmpty();
        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? AND target_type = 'user' ORDER BY id",
                String.class, adminId)).containsExactly("USER_ORDER_LIMIT_SET", "USER_MARKET_LIMIT_SET",
                "USER_MARKET_LIMIT_REMOVED");

        jdbc.update("UPDATE admin_users SET role = 'VIEWER' WHERE id = ?", adminId);
        assertThat(get(session, "/admin/users/" + sub + "/overrides").get("orderLimit").get("own").get("maxOrders").asInt())
                .isEqualTo(5000);
        assertThat(call(session, HttpMethod.DELETE, "/admin/users/" + sub + "/overrides/order-limit", null, null)
                .getStatusCode().value()).isEqualTo(403);
    }

    private static Map<String, Object> body(String symbol, Integer orders, Integer stops, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("symbol", symbol);
        body.put("maxOrders", orders);
        body.put("maxStops", stops);
        body.put("reason", reason);
        return body;
    }

    private long user(String email, String type, Long parent) {
        jdbc.update("INSERT INTO user_users (id, email, email_verified, status, type, parent_user_id, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, 'ACTIVE', ?, ?, 'BASIC', TRUE)", Uid.next(), email, type, parent);
        return jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, email);
    }

    private String code(int seconds) {
        return Totp.codeAt(secret, Instant.now().plusSeconds(seconds));
    }

    private JsonNode get(String session, String path) {
        return call(session, HttpMethod.GET, path, null, null).getBody().get("data");
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", tag + "@x.dev", "password", PASSWORD), null);
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
