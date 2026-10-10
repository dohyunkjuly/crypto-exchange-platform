package com.exchange.admin.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
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
 * The whole login over HTTP, against the real database: password, enrolment, code, a
 * role-gated read, a step-up write, logout. The admin is made the way the CLI makes one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class AdminLoginIT {

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

    @BeforeEach
    void anAdminExists() {
        email = "it-" + UUID.randomUUID().toString().substring(0, 8) + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.SUPERADMIN)).getId();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ? OR target_id = ?", adminId, email);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("password, enrol, code, then a gated read works, a step-up write needs a fresh code, and logout ends it")
    void theWholeLogin() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login",
                Map.of("email", email, "password", PASSWORD));
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getBody().get("data").get("next").asText()).isEqualTo("ENROL");
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith("admin_session=").contains("HttpOnly");

        // Half logged in is not logged in.
        assertThat(call(cookie, HttpMethod.GET, "/admin/auth/me", null).getStatusCode().value()).isEqualTo(401);
        assertThat(call(cookie, HttpMethod.GET, "/admin/admins", null).getStatusCode().value()).isEqualTo(401);

        ResponseEntity<JsonNode> enrol = call(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of());
        String uri = enrol.getBody().get("data").get("provisioningUri").asText();
        // The authenticator shows the operator's name, from configuration -- never a brand.
        assertThat(uri).startsWith("otpauth://totp/Example%20Exchange%20Admin:");
        String secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");

        assertThat(call(cookie, HttpMethod.POST, "/admin/auth/totp/verify", Map.of("code", "000000"))
                .getStatusCode().value()).isEqualTo(401);
        // One instant, one code: recomputed later, "the login's code" could fall in the next
        // 30-second step and be a fresh, unspent one.
        Instant loggedInAt = Instant.now();
        String loginCode = Totp.codeAt(secret, loggedInAt);
        ResponseEntity<JsonNode> verified = call(cookie, HttpMethod.POST, "/admin/auth/totp/verify",
                Map.of("code", loginCode));
        assertThat(verified.getStatusCode().value()).isEqualTo(200);
        assertThat(verified.getBody().get("data").get("email").asText()).isEqualTo(email);

        assertThat(call(cookie, HttpMethod.GET, "/admin/auth/me", null).getStatusCode().value()).isEqualTo(200);
        assertThat(call(cookie, HttpMethod.GET, "/admin/admins", null).getStatusCode().value()).isEqualTo(200);

        // The login's code is spent; the step-up needs the next one.
        ResponseEntity<JsonNode> stale = call(cookie, HttpMethod.PATCH, "/admin/admins/" + adminId + "/role",
                Map.of("role", "OPERATOR"), loginCode);
        assertThat(stale.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<JsonNode> none = call(cookie, HttpMethod.PATCH, "/admin/admins/" + adminId + "/role",
                Map.of("role", "OPERATOR"), null);
        assertThat(none.getBody().get("code").asInt()).isEqualTo(2021);
        ResponseEntity<JsonNode> fresh = call(cookie, HttpMethod.PATCH, "/admin/admins/" + adminId + "/role",
                Map.of("role", "OPERATOR"), Totp.codeAt(secret, loggedInAt.plusSeconds(30)));
        assertThat(fresh.getStatusCode().value()).isEqualTo(200);
        assertThat(fresh.getBody().get("data").get("role").asText()).isEqualTo("OPERATOR");

        // Now an OPERATOR: the SUPERADMIN-only list is a 403, not a 401.
        assertThat(call(cookie, HttpMethod.GET, "/admin/admins", null).getStatusCode().value()).isEqualTo(403);

        List<Map<String, Object>> audited = jdbc.queryForList(
                "SELECT action FROM admin_audit_log WHERE admin_id = ? ORDER BY id", adminId);
        assertThat(audited).extracting(row -> row.get("action"))
                .containsExactly("TOTP_ENROLLED", "LOGIN", "ADMIN_ROLE_CHANGED");

        assertThat(call(cookie, HttpMethod.POST, "/admin/auth/logout", Map.of()).getStatusCode().value())
                .isEqualTo(200);
        assertThat(call(cookie, HttpMethod.GET, "/admin/auth/me", null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("a wrong password five times locks the address, and every attempt is on record")
    void lockout() {
        for (int i = 0; i < 5; i++) {
            assertThat(call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", email, "password", "no"))
                    .getBody().get("code").asInt()).isEqualTo(2001);
        }
        assertThat(call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", email, "password", PASSWORD))
                .getBody().get("code").asInt()).isEqualTo(2002);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE target_id = ?", Long.class, email))
                .isEqualTo(6);
    }

    private ResponseEntity<JsonNode> call(String cookie, HttpMethod method, String path, Object body) {
        return call(cookie, method, path, body, null);
    }

    private ResponseEntity<JsonNode> call(String cookie, HttpMethod method, String path, Object body, String totp) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie.split(";")[0]);
        }
        if (totp != null) {
            headers.add("X-Admin-TOTP", totp);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
