package com.exchange.admin.bff.orders;

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
 * Every customer's orders, found by staff over HTTP, and never changed by them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class OrdersIT {

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("port " + port + " not reachable -- run `docker compose up -d`");
            }
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

    private final String tag = "orders-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long adminId;
    private Long customer;
    private Long symbolId;
    private String symbol;
    private UUID open;
    private UUID filled;
    private UUID trade;
    private String base;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(tag + "@x.dev", passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        jdbc.update("INSERT INTO user_users (id, email, email_verified, status, type, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, 'ACTIVE', 'PRIMARY', 'BASIC', TRUE)", Uid.next(), tag + "-c@x.dev");
        customer = jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, tag + "-c@x.dev");
        Map<String, Object> market = jdbc.queryForMap("SELECT s.id, s.symbol, s.base_asset_id, a.symbol AS base "
                + "FROM spot_symbols s JOIN wallet_assets a ON a.id = s.base_asset_id ORDER BY s.id LIMIT 1");
        base = (String) market.get("base");
        symbolId = ((Number) market.get("id")).longValue();
        symbol = (String) market.get("symbol");
        open = order("NEW");
        filled = order("FILLED");
        // Somebody else sold it: the seller's side is not this order's to show.
        trade = Ulid.randomUuid();
        jdbc.update("INSERT INTO spot_trades (id, symbol_id, price, quantity, quote_quantity, buyer_order_id, seller_order_id, "
                + "buyer_user_id, seller_user_id, buyer_is_maker, buyer_fee, buyer_fee_asset_id, seller_fee, sequence, executed_at) "
                + "VALUES (?, ?, 100000000, 100000000, 100000000, ?, ?, ?, ?, FALSE, 100000, ?, 999, ?, NOW(6))",
                bytes(trade), symbolId, bytes(filled), bytes(Ulid.randomUuid()), customer, -customer,
                market.get("base_asset_id"), Long.MAX_VALUE - (System.nanoTime() & 0xFFFFFFFL));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        jdbc.update("DELETE FROM spot_trades WHERE id = ?", bytes(trade));
        jdbc.update("DELETE FROM spot_orders WHERE user_id = ?", customer);
        jdbc.update("DELETE FROM user_users WHERE id = ?", customer);
    }

    @Test
    @DisplayName("finds a customer's orders by email and state, and opens one with its own side of each fill")
    void search() {
        String session = signIn();

        JsonNode all = get(session, "/admin/orders?customer=" + tag + "-c@x.dev");
        assertThat(all.get("total").asLong()).isEqualTo(2);
        assertThat(all.get("items").get(0).get("userEmail").asText()).isEqualTo(tag + "-c@x.dev");
        assertThat(all.get("items").get(0).get("symbol").asText()).isEqualTo(symbol);
        assertThat(all.get("items").get(0).get("quote").asText()).isNotBlank();

        JsonNode openOnly = get(session, "/admin/orders?customer=" + customer + "&state=open");
        assertThat(openOnly.get("total").asLong()).isEqualTo(1);
        assertThat(openOnly.get("items").get(0).get("id").asText()).isEqualTo(open.toString());

        JsonNode detail = get(session, "/admin/orders/" + open);
        assertThat(detail.get("order").get("status").asText()).isEqualTo("NEW");
        assertThat(detail.get("fills")).isEmpty();

        JsonNode fills = get(session, "/admin/orders/" + filled).get("fills");
        assertThat(fills).hasSize(1);
        assertThat(fills.get(0).get("fee").asText()).isEqualTo("0.001");
        assertThat(fills.get(0).get("feeAsset").asText()).isEqualTo(base);
        assertThat(fills.get(0).get("maker").asBoolean()).isFalse();

        // Nobody by that name: an empty page, not every order on the exchange.
        assertThat(get(session, "/admin/orders?customer=nobody-" + tag + "@x.dev").get("total").asLong()).isZero();
        assertThat(call(session, HttpMethod.GET, "/admin/orders?state=SIDEWAYS", null).getBody().get("code").asInt())
                .isEqualTo(5030);
    }

    @Test
    @DisplayName("there is no way to cancel a customer's order, whatever the role")
    void noCancel() {
        jdbc.update("UPDATE admin_users SET role = 'SUPERADMIN' WHERE id = ?", adminId);
        String session = signIn();
        int status = call(session, HttpMethod.POST, "/admin/orders/" + open + "/cancel", Map.of("reason", "x"))
                .getStatusCode().value();
        assertThat(status).isIn(404, 405);
        assertThat(jdbc.queryForObject("SELECT status FROM spot_orders WHERE id = ?", String.class, bytes(open)))
                .isEqualTo("NEW");
    }

    private UUID order(String status) {
        UUID id = Ulid.randomUuid();
        jdbc.update("INSERT INTO spot_orders (id, user_id, symbol_id, side, order_type, time_in_force, price, quantity, "
                + "status, maker_fee_bps, taker_fee_bps) VALUES (?, ?, ?, 'BUY', 'LIMIT', 'GTC', 100000000, 100000000, ?, 10, 10)",
                bytes(id), customer, symbolId, status);
        return id;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private JsonNode get(String session, String path) {
        return call(session, HttpMethod.GET, path, null).getBody().get("data");
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
