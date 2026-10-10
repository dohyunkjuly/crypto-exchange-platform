package com.exchange.admin.bff.kyc;

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
 * The compliance queue over HTTP: a submission waits, is approved with a fresh code, and the
 * gate's read model on the user row moves in the same transaction as the audit row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class KycIT {

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
    private String secret;
    private final String tag = "kyc-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long customer;

    @BeforeEach
    void seed() {
        email = tag + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        jdbc.update("INSERT INTO user_users (id, email, email_verified, password_hash, status, type, kyc_level, mfa_enabled) "
                + "VALUES (?, ?, TRUE, NULL, 'ACTIVE', 'PRIMARY', 'NONE', FALSE)", Uid.next(), tag + "-c@x.dev");
        customer = jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, tag + "-c@x.dev");
        jdbc.update("INSERT INTO user_kyc_profiles (user_id, status, level, legal_first_name, legal_last_name, "
                + "date_of_birth, nationality, residence_country, submitted_at) "
                + "VALUES (?, 'PENDING', 'NONE', 'Ann', 'Example', '1990-01-02', 'KR', 'KR', NOW(6))", customer);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
        jdbc.update("DELETE FROM user_auth_events WHERE user_id = ?", customer);
        jdbc.update("DELETE FROM user_kyc_profiles WHERE user_id = ?", customer);
        jdbc.update("DELETE FROM user_users WHERE id = ?", customer);
    }

    @Test
    @DisplayName("a pending submission is queued oldest first, approval needs a fresh code, and it opens the gate")
    void approveFromTheQueue() {
        String session = signIn();

        JsonNode queue = call(session, HttpMethod.GET, "/admin/kyc", null, null).getBody().get("data");
        JsonNode mine = null;
        for (JsonNode item : queue.get("items")) {
            if (item.get("user").get("id").asLong() == customer) {
                mine = item;
            }
        }
        assertThat(mine).as("the submission is in the PENDING queue").isNotNull();
        assertThat(mine.get("legalFirstName").asText()).isEqualTo("Ann");

        // Without the code: refused, and nothing moved.
        ResponseEntity<JsonNode> noCode = call(session, HttpMethod.POST, "/admin/kyc/" + customer + "/approve",
                Map.of("level", "BASIC"), null);
        assertThat(noCode.getBody().get("code").asInt()).isEqualTo(2021);
        assertThat(kycLevel()).isEqualTo("NONE");

        ResponseEntity<JsonNode> approved = call(session, HttpMethod.POST, "/admin/kyc/" + customer + "/approve",
                Map.of("level", "BASIC"), Totp.codeAt(secret, Instant.now()));
        assertThat(approved.getStatusCode().value()).isEqualTo(200);
        assertThat(approved.getBody().get("data").get("status").asText()).isEqualTo("APPROVED");
        // The gate's read model on the user row, not just the profile.
        assertThat(kycLevel()).isEqualTo("BASIC");

        List<String> audited = jdbc.queryForList("SELECT action FROM admin_audit_log WHERE admin_id = ? "
                + "AND target_type = 'user' ORDER BY id", String.class, adminId);
        assertThat(audited).containsExactly("KYC_APPROVED");

        // Approving to NONE is a rejection by another name, and is refused as such.
        assertThat(call(session, HttpMethod.POST, "/admin/kyc/" + customer + "/approve",
                Map.of("level", "NONE"), Totp.codeAt(secret, Instant.now().plusSeconds(30)))
                .getBody().get("code").asInt()).isEqualTo(2027);
    }

    @Test
    @DisplayName("a rejection closes the gate and keeps its reason for the customer")
    void rejectWithAReason() {
        String session = signIn();
        jdbc.update("UPDATE user_users SET kyc_level = 'BASIC' WHERE id = ?", customer);

        ResponseEntity<JsonNode> rejected = call(session, HttpMethod.POST, "/admin/kyc/" + customer + "/reject",
                Map.of("reason", "Name does not match the document"), Totp.codeAt(secret, Instant.now()));
        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        assertThat(rejected.getBody().get("data").get("rejectionReason").asText()).isEqualTo("Name does not match the document");
        assertThat(kycLevel()).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT after_json FROM admin_audit_log WHERE admin_id = ? AND action = 'KYC_REJECTED'",
                String.class, adminId)).contains("Name does not match");
    }

    private String kycLevel() {
        return jdbc.queryForObject("SELECT kyc_level FROM user_users WHERE id = ?", String.class, customer);
    }

    private String signIn() {
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login", Map.of("email", email, "password", PASSWORD), null);
        String cookie = login.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
        String uri = call(cookie, HttpMethod.POST, "/admin/auth/totp/enrol", Map.of(), null).getBody().get("data").get("provisioningUri").asText();
        secret = uri.replaceAll(".*secret=([A-Z2-7]+).*", "$1");
        // One step back for the login, so a test has the current step and the next for two writes:
        // the window is one step either side, and a spent step is never accepted again.
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
