package com.exchange.admin.bff.fees;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
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
 * The fee schedule over HTTP. The schedule is shared seed data, so each test puts it back exactly
 * as it found it. A test has two authenticator codes, so each covers at most two writes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class FeesIT {

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

    private final String email = "fees-it-" + UUID.randomUUID().toString().substring(0, 8) + "@x.dev";
    private Long adminId;
    private String secret;
    private List<Map<String, Object>> schedule;

    @BeforeEach
    void seed() {
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        schedule = jdbc.queryForList("SELECT level, name, min_30d_volume, maker_fee_bps, taker_fee_bps "
                + "FROM fee_tiers WHERE product = 'SPOT'");
    }

    @AfterEach
    void cleanUp() {
        // A level a test added goes; the ones that were there are put back as they were.
        jdbc.update("DELETE FROM fee_tiers WHERE product = 'SPOT' AND level NOT IN ("
                + String.join(",", schedule.stream().map(r -> r.get("level").toString()).toList()) + ")");
        for (Map<String, Object> row : schedule) {
            jdbc.update("UPDATE fee_tiers SET name = ?, min_30d_volume = ?, maker_fee_bps = ?, taker_fee_bps = ? "
                    + "WHERE product = 'SPOT' AND level = ?", row.get("name"), row.get("min_30d_volume"),
                    row.get("maker_fee_bps"), row.get("taker_fee_bps"), row.get("level"));
        }
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("the schedule is listed level by level, and a level changes with a code, audited as it was and became")
    void listAndChange() {
        String session = signIn();
        JsonNode levels = get(session, "/admin/fees");
        assertThat(levels.get(0).get("level").asInt()).isZero();
        assertThat(levels.get(0).get("min30dVolume").asText()).isEqualTo("0");

        String volume = levels.get(1).get("min30dVolume").asText();
        JsonNode changed = call(session, HttpMethod.PATCH, "/admin/fees/1",
                Map.of("name", "Silver", "min30dVolume", volume, "makerFeeBps", -1, "takerFeeBps", 7), code(0))
                .getBody().get("data");
        assertThat(changed.get("name").asText()).isEqualTo("Silver");
        assertThat(changed.get("makerFeeBps").asInt()).isEqualTo(-1);
        assertThat(jdbc.queryForObject("SELECT taker_fee_bps FROM fee_tiers WHERE product = 'SPOT' AND level = 1",
                Integer.class)).isEqualTo(7);
        String audit = jdbc.queryForObject("SELECT CONCAT(before_json, ' -> ', after_json) FROM admin_audit_log "
                + "WHERE admin_id = ? AND action = 'FEE_LEVEL_CHANGED'", String.class, adminId);
        assertThat(audit).contains("\"takerFeeBps\":10").contains("\"name\":\"Silver\"");

        // A rebate bigger than the fee on the other side would make every trade cost the exchange.
        ResponseEntity<JsonNode> costly = call(session, HttpMethod.PATCH, "/admin/fees/1",
                Map.of("name", "Silver", "min30dVolume", volume, "makerFeeBps", -8, "takerFeeBps", 7), code(30));
        assertThat(costly.getBody().get("code").asInt()).isEqualTo(5033);
        assertThat(costly.getBody().get("message").asText()).contains("rebate");
    }

    @Test
    @DisplayName("a new level goes on top; a write needs a code and an OPERATOR")
    void addAndGuards() {
        String session = signIn();
        int top = jdbc.queryForObject("SELECT MAX(level) FROM fee_tiers WHERE product = 'SPOT'", Integer.class);
        String topVolume = jdbc.queryForObject("SELECT min_30d_volume FROM fee_tiers WHERE product = 'SPOT' AND level = ?",
                String.class, top);
        Map<String, Object> next = Map.of("name", "Top", "min30dVolume",
                new BigDecimal(topVolume).movePointLeft(8).add(BigDecimal.ONE).toPlainString(),
                "makerFeeBps", 0, "takerFeeBps", 1);

        assertThat(call(session, HttpMethod.POST, "/admin/fees", next, null).getBody().get("code").asInt())
                .isEqualTo(2021);
        JsonNode added = call(session, HttpMethod.POST, "/admin/fees", next, code(0)).getBody().get("data");
        assertThat(added.get("level").asInt()).isEqualTo(top + 1);

        jdbc.update("UPDATE admin_users SET role = 'VIEWER' WHERE id = ?", adminId);
        assertThat(call(session, HttpMethod.PATCH, "/admin/fees/0",
                Map.of("name", "VIP 0", "min30dVolume", "0", "makerFeeBps", 0, "takerFeeBps", 0), code(30))
                .getStatusCode().value()).isEqualTo(403);
    }

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
