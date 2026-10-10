package com.exchange.admin.bff.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.bff.testing.Uid;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.exchange.common.id.Ulid;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
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
 * The dashboard's figures, each from where it is kept. The database is shared with every other
 * test and with whatever a developer has traded, so this measures what its own rows add rather
 * than what the exchange happens to hold when it runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class DashboardIT {

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

    private final String tag = "dash-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long adminId;
    private Long customer;
    private Long symbolId;
    private String symbol;
    private UUID tradeId;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(tag + "@x.dev", passwords.encode(PASSWORD), AdminRole.VIEWER)).getId();
        Map<String, Object> market = jdbc.queryForMap("SELECT s.id, s.symbol FROM spot_symbols s "
                + "JOIN wallet_assets q ON q.id = s.quote_asset_id WHERE q.symbol = 'USDT' ORDER BY s.id LIMIT 1");
        symbolId = ((Number) market.get("id")).longValue();
        symbol = (String) market.get("symbol");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        if (tradeId != null) jdbc.update("DELETE FROM spot_trades WHERE id = ?", bytes(tradeId));
        if (customer != null) {
            jdbc.update("DELETE FROM user_users WHERE parent_user_id = ?", customer);
            jdbc.update("DELETE FROM user_users WHERE id = ?", customer);
        }
    }

    @Test
    @DisplayName("a signup and a trade today move today's figures and the pair's row; a sub-account is not a signup")
    void todaySoFar() {
        String session = signIn();
        JsonNode before = get(session);

        jdbc.update("INSERT INTO user_users (id, email, email_verified, status, type, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, 'ACTIVE', 'PRIMARY', 'NONE', FALSE)", Uid.next(), tag + "-c@x.dev");
        customer = jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, tag + "-c@x.dev");
        jdbc.update("INSERT INTO user_users (id, email, email_verified, status, type, parent_user_id, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, 'ACTIVE', 'SUB', ?, 'NONE', FALSE)", Uid.next(), tag + "-s_virtual@x.dev", customer);

        tradeId = Ulid.randomUuid();
        jdbc.update("INSERT INTO spot_trades (id, symbol_id, price, quantity, quote_quantity, buyer_order_id, seller_order_id, "
                + "buyer_user_id, seller_user_id, buyer_is_maker, sequence, executed_at) "
                + "VALUES (?, ?, 100000000, 100000000, 25000000000, ?, ?, ?, ?, FALSE, ?, NOW(6))",
                bytes(tradeId), symbolId, bytes(UUID.randomUUID()), bytes(UUID.randomUUID()), customer, customer + 1,
                -Math.abs(System.nanoTime()));

        JsonNode after = get(session);
        assertThat(delta(before, after, "signups")).as("the sub-account is not counted").isEqualByComparingTo("1");
        assertThat(delta(before, after, "trades")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "volume")).isEqualByComparingTo("250");

        JsonNode pair = null;
        for (JsonNode p : after.get("pairs")) if (p.get("symbol").asText().equals(symbol)) pair = p;
        assertThat(pair).as("the market that traded has a row").isNotNull();
        assertThat(pair.get("quote").asText()).isEqualTo("USDT");
        // Yesterday is yesterday up to now, so a trade made a moment ago is never in it.
        assertThat(after.get("volume").get("yesterday").asText()).isEqualTo(before.get("volume").get("yesterday").asText());
    }

    private static BigDecimal delta(JsonNode before, JsonNode after, String field) {
        return new BigDecimal(after.get(field).get("today").asText()).subtract(new BigDecimal(before.get(field).get("today").asText()));
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private JsonNode get(String session) {
        return call(session, HttpMethod.GET, "/admin/dashboard", null).getBody().get("data");
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", tag + "@x.dev", "password", PASSWORD));
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = call(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of()).getBody().get("data").get("provisioningUri").asText();
        String secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        call(cookie, HttpMethod.POST, "/admin/auth/totp/verify", Map.of("code", Totp.codeAt(secret, Instant.now())));
        return cookie;
    }

    private ResponseEntity<JsonNode> call(String cookie, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) headers.add(HttpHeaders.COOKIE, cookie);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
