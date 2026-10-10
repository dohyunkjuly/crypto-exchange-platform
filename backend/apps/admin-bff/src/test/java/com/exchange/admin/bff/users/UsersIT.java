package com.exchange.admin.bff.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.bff.testing.Uid;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Timestamp;
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
 * Staff working a customer, over HTTP: find them, see their subs under them, freeze the lot,
 * thaw what the freeze took down and nothing else -- with an audit row for every write.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class UsersIT {

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

    private String email;
    private Long adminId;
    private final String tag = "users-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long master;
    private Long quietSub;
    private Long frozenSub;

    @BeforeEach
    void seed() {
        email = tag + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        master = user(tag + "-master@x.dev", "PRIMARY", null, "ACTIVE");
        quietSub = user(tag + "-a_virtual@x.dev", "SUB", master, "ACTIVE");
        frozenSub = user(tag + "-b_virtual@x.dev", "SUB", master, "SUSPENDED");
        jdbc.update("UPDATE user_users SET frozen_reason = 'chargeback' WHERE id = ?", frozenSub);
        // Two live sessions on the master, so a freeze has something to revoke.
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO user_sessions (user_id, token_hash, ip_address, created_at, last_seen_at, expires_at) "
                    + "VALUES (?, ?, '127.0.0.1', NOW(6), NOW(6), DATE_ADD(NOW(6), INTERVAL 1 DAY))",
                    master, UUID.randomUUID().toString().replace("-", "").getBytes());
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        jdbc.update("DELETE FROM user_sessions WHERE user_id IN (?, ?, ?)", master, quietSub, frozenSub);
        jdbc.update("DELETE FROM user_users WHERE id IN (?, ?)", quietSub, frozenSub);
        jdbc.update("DELETE FROM user_users WHERE id = ?", master);
    }

    @Test
    @DisplayName("subs are found under their master, a freeze takes them down with it, and a thaw spares one frozen on its own")
    void theWholeFreeze() {
        String session = signIn();

        // The list is primary accounts: the master is there, the subs are not.
        JsonNode list = get(session, "/admin/users?q=" + tag).getBody().get("data");
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("email").asText()).endsWith("-master@x.dev");

        JsonNode detail = get(session, "/admin/users/" + master).getBody().get("data");
        assertThat(detail.get("subAccounts")).hasSize(2);
        assertThat(detail.get("sessions")).hasSize(2);

        // Freeze: the master and its active sub, marked as taken down together; the sub that was
        // already frozen keeps its own reason.
        ResponseEntity<JsonNode> frozen = post(session, "/admin/users/" + master + "/freeze", Map.of("reason", "fraud review"));
        assertThat(frozen.getStatusCode().value()).isEqualTo(200);
        assertThat(frozen.getBody().get("data").get("subsAffected")).hasSize(1);
        assertThat(frozen.getBody().get("data").get("sessionsRevoked").asInt()).isEqualTo(2);
        assertThat(status(quietSub)).isEqualTo("SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT frozen_with_master FROM user_users WHERE id = ?", Boolean.class, quietSub)).isTrue();
        assertThat(jdbc.queryForObject("SELECT frozen_reason FROM user_users WHERE id = ?", String.class, frozenSub)).isEqualTo("chargeback");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, master)).isZero();

        // Freezing again is a conflict, not a second audit row.
        assertThat(post(session, "/admin/users/" + master + "/freeze", Map.of("reason", "again"))
                .getBody().get("code").asInt()).isEqualTo(2025);

        // Thaw: the master and the sub it took down come back; the deliberately frozen sub does not.
        post(session, "/admin/users/" + master + "/unfreeze", Map.of());
        assertThat(status(master)).isEqualTo("ACTIVE");
        assertThat(status(quietSub)).isEqualTo("ACTIVE");
        assertThat(status(frozenSub)).isEqualTo("SUSPENDED");

        List<String> audited = jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? "
                + "AND target_type = 'user' ORDER BY id", String.class, adminId);
        assertThat(audited).containsExactly("USER_FROZEN", "USER_UNFROZEN");
        assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE admin_id = ? AND action = 'USER_FROZEN'",
                String.class, adminId)).contains("fraud review").contains("\"subs\":[" + quietSub + "]");
    }

    @Test
    @DisplayName("suspending trading records why and keeps the account ACTIVE; the subs' rows are not touched")
    void suspendTrading() {
        String session = signIn();

        ResponseEntity<JsonNode> suspended = post(session, "/admin/users/" + master + "/suspend-trading",
                Map.of("reason", "wash trading review"));
        assertThat(suspended.getStatusCode().value()).isEqualTo(200);
        JsonNode user = suspended.getBody().get("data");
        assertThat(user.get("tradingSuspendedReason").asText()).isEqualTo("wash trading review");
        assertThat(user.get("tradingSuspendedAt").isNull()).isFalse();
        assertThat(status(master)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, master)).as("still logged in").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT trading_suspended_at FROM user_users WHERE id = ?", Timestamp.class,
                quietSub)).as("the gate reads the master for a sub").isNull();

        assertThat(post(session, "/admin/users/" + master + "/suspend-trading", Map.of("reason", "again"))
                .getBody().get("code").asInt()).isEqualTo(2025);
        assertThat(post(session, "/admin/users/" + master + "/suspend-trading", Map.of("reason", " "))
                .getStatusCode().value()).isEqualTo(400);

        ResponseEntity<JsonNode> resumed = post(session, "/admin/users/" + master + "/resume-trading", Map.of());
        assertThat(resumed.getBody().get("data").get("tradingSuspendedAt").isNull()).isTrue();

        assertThat(jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? AND target_type = 'user' ORDER BY id",
                String.class, adminId)).containsExactly("USER_TRADING_SUSPENDED", "USER_TRADING_RESUMED");
        assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE admin_id = ? AND action = 'USER_TRADING_SUSPENDED'",
                String.class, adminId)).contains("wash trading review");
    }

    @Test
    @DisplayName("a VIEWER may read a customer and may not freeze one or suspend its trading")
    void viewersRead() {
        jdbc.update("UPDATE admin_users SET role = 'VIEWER' WHERE id = ?", adminId);
        String session = signIn();

        assertThat(get(session, "/admin/users/" + master).getStatusCode().value()).isEqualTo(200);
        assertThat(post(session, "/admin/users/" + master + "/freeze", Map.of("reason", "x"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(post(session, "/admin/users/" + master + "/suspend-trading", Map.of("reason", "x"))
                .getStatusCode().value()).isEqualTo(403);
        assertThat(status(master)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the exchange's own accounts are not customers here")
    void houseIsNotACustomer() {
        String session = signIn();
        Long fees = jdbc.queryForObject("SELECT id FROM user_users WHERE email = 'fees@exchange'", Long.class);
        assertThat(get(session, "/admin/users/" + fees).getBody().get("code").asInt()).isEqualTo(2024);
        assertThat(get(session, "/admin/users?q=fees@exchange").getBody().get("data").get("total").asLong()).isZero();
    }

    private Long user(String email, String type, Long parent, String status) {
        jdbc.update("INSERT INTO user_users (id, email, email_verified, password_hash, status, type, parent_user_id, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, NULL, ?, ?, ?, 'NONE', FALSE)", Uid.next(), email, status, type, parent);
        return jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, email);
    }

    private String status(Long id) {
        return jdbc.queryForObject("SELECT status FROM user_users WHERE id = ?", String.class, id);
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = post(null, "/admin/auth/login", Map.of("email", email, "password", PASSWORD));
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = post(cookie, "/admin/auth/totp/enrol", Map.of()).getBody().get("data").get("provisioningUri").asText();
        String secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        post(cookie, "/admin/auth/totp/verify", Map.of("code", Totp.codeAt(secret, Instant.now())));
        return cookie;
    }

    private ResponseEntity<JsonNode> get(String cookie, String path) {
        return exchange(cookie, HttpMethod.GET, path, null);
    }

    private ResponseEntity<JsonNode> post(String cookie, String path, Object body) {
        return exchange(cookie, HttpMethod.POST, path, body);
    }

    private ResponseEntity<JsonNode> exchange(String cookie, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
