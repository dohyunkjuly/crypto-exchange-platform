package com.exchange.admin.bff.copy;

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
 * Who may lead, over HTTP (docs/reference/copy-trading.md): an application waits, each decision
 * needs a fresh code and is audited with its reason, and approval is what makes the lead account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class CopyLeadersIT {

    private static final String PASSWORD = "correct-horse-battery";

    @BeforeAll
    static void requireInfrastructure() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 3308), 500);
        } catch (Exception e) {
            Assumptions.abort("port 3308 not reachable -- run `docker compose up -d`");
        }
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private AdminUserRepository admins;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JdbcTemplate jdbc;

    private final String tag = "leaders-it-" + UUID.randomUUID().toString().substring(0, 8);
    private final UUID leaderId = Ulid.randomUuid();
    private String email;
    private Long adminId;
    private String secret;
    private Long customer;

    @BeforeEach
    void seed() {
        email = tag + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        customer = Uid.next();
        jdbc.update("INSERT INTO user_users (id, email, email_verified, password_hash, status, type, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, NULL, 'ACTIVE', 'PRIMARY', 'BASIC', TRUE)", customer, tag + "-c@x.dev");
        jdbc.update("INSERT INTO copy_leaders (id, user_id, display_name, bio, show_portfolio, status) "
                + "VALUES (?, ?, ?, 'trades the majors', TRUE, 'ACTIVE')", Ulid.toBytes(leaderId), customer, tag);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        jdbc.update("DELETE FROM copy_leaders WHERE id = ?", (Object) Ulid.toBytes(leaderId));
        jdbc.update("DELETE FROM user_auth_events WHERE user_id = ?", customer);
        // The lead account first: parent_user_id is a real foreign key, same domain.
        jdbc.update("DELETE FROM user_users WHERE parent_user_id = ?", customer);
        jdbc.update("DELETE FROM user_users WHERE id = ?", customer);
    }

    @Test
    @DisplayName("staff see whose leader it is; suspending needs a fresh code, keeps its reason and is audited; "
            + "a suspension is refused on a leader not leading")
    void suspend() {
        String session = signIn();

        JsonNode leading = mine(call(session, HttpMethod.GET, "/admin/copy/leaders?status=ACTIVE", null, null));
        assertThat(leading.get("email").asText()).as("staff see whose leader it is").isEqualTo(tag + "-c@x.dev");

        // Without the code: refused, and nothing changed.
        assertThat(call(session, HttpMethod.POST, "/admin/copy/leaders/" + leaderId + "/suspend",
                Map.of("reason", "x"), null).getBody().get("code").asInt()).isEqualTo(2021);
        assertThat(status()).isEqualTo("ACTIVE");

        JsonNode suspended = call(session, HttpMethod.POST, "/admin/copy/leaders/" + leaderId + "/suspend",
                Map.of("reason", "wash trading with a second account"), Totp.codeAt(secret, Instant.now()))
                .getBody().get("data");
        assertThat(suspended.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("reviewReason").asText()).isEqualTo("wash trading with a second account");
        assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE admin_id = ? "
                + "AND action = 'COPY_LEADER_SUSPENDED'", String.class, adminId)).contains("wash trading");

        ResponseEntity<JsonNode> again = call(session, HttpMethod.POST, "/admin/copy/leaders/" + leaderId + "/suspend",
                Map.of("reason", "again"), Totp.codeAt(secret, Instant.now().plusSeconds(30)));
        assertThat(again.getBody().get("code").asInt()).isEqualTo(8014);
    }

    @Test
    @DisplayName("a suspended leader is reinstated, and the list of everyone shows them")
    void reinstate() {
        jdbc.update("UPDATE copy_leaders SET status = 'SUSPENDED' WHERE id = ?", (Object) Ulid.toBytes(leaderId));
        String session = signIn();

        assertThat(call(session, HttpMethod.POST, "/admin/copy/leaders/" + leaderId + "/reinstate", null,
                Totp.codeAt(secret, Instant.now())).getBody().get("data").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(mine(call(session, HttpMethod.GET, "/admin/copy/leaders?size=100", null, null)).get("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(call(session, HttpMethod.POST, "/admin/copy/leaders/" + UUID.randomUUID() + "/reinstate", null,
                Totp.codeAt(secret, Instant.now().plusSeconds(30))).getBody().get("code").asInt()).isEqualTo(8001);
    }

    @Test
    @DisplayName("staff give a leader a profit share of their own and take it back, each with a fresh code, audited")
    void profitShare() {
        // HttpURLConnection, TestRestTemplate's default, cannot send PATCH.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        String session = signIn();
        String path = "/admin/copy/leaders/" + leaderId + "/profit-share";
        assertThat(mine(call(session, HttpMethod.GET, "/admin/copy/leaders?status=ACTIVE", null, null))
                .get("profitShareBps").isNull()).as("on the exchange's rate until set").isTrue();

        assertThat(call(session, HttpMethod.PATCH, path, Map.of("profitShareBps", 1500), null).getBody().get("code").asInt())
                .isEqualTo(2021);
        JsonNode set = call(session, HttpMethod.PATCH, path, Map.of("profitShareBps", 1500), Totp.codeAt(secret, Instant.now()))
                .getBody().get("data");
        assertThat(set.get("profitShareBps").asInt()).isEqualTo(1500);
        assertThat(jdbc.queryForObject("SELECT profit_share_bps FROM copy_leaders WHERE id = ?", Integer.class,
                (Object) Ulid.toBytes(leaderId))).isEqualTo(1500);

        JsonNode cleared = call(session, HttpMethod.PATCH, path, java.util.Collections.singletonMap("profitShareBps", null),
                Totp.codeAt(secret, Instant.now().plusSeconds(30))).getBody().get("data");
        assertThat(cleared.get("profitShareBps").isNull()).as("back on the exchange's").isTrue();

        assertThat(jdbc.queryForList("SELECT CONCAT(COALESCE(before_json, '-'), ' -> ', after_json) FROM admin_audit_log "
                + "WHERE admin_id = ? AND action = 'COPY_LEADER_PROFIT_SHARE_SET' ORDER BY id", String.class, adminId))
                .containsExactly("{\"profitShareBps\":null} -> {\"profitShareBps\":1500}",
                        "{\"profitShareBps\":1500} -> {\"profitShareBps\":null}");
    }

    private JsonNode mine(ResponseEntity<JsonNode> listed) {
        for (JsonNode item : listed.getBody().get("data").get("items")) {
            if (item.get("id").asText().equals(leaderId.toString())) {
                return item;
            }
        }
        throw new AssertionError("the leader is not in the list: " + listed.getBody());
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM copy_leaders WHERE id = ?", String.class, (Object) Ulid.toBytes(leaderId));
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", email, "password", PASSWORD), null);
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = call(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of(), null).getBody().get("data").get("provisioningUri").asText();
        secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        // One step back for the login, so a test has the current step and the next for two writes.
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
