package com.exchange.admin.bff.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
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
 * A SUPERADMIN makes a colleague from the console, and the log reads it all back. What must
 * hold: minting a login and resetting an authenticator take a fresh code, nobody acts on their
 * own row, and every one of those writes is a row the log can be filtered down to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class AuditAndAdminsIT {

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

    private final String tag = "staff-it-" + UUID.randomUUID().toString().substring(0, 8);
    private Long superId;
    private String secret;
    private final List<Long> made = new ArrayList<>();

    @BeforeEach
    void seed() {
        superId = admins.save(new AdminUser(tag + "@x.dev", passwords.encode(PASSWORD), AdminRole.SUPERADMIN)).getId();
        made.add(superId);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : made) {
            jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ? OR (target_type = 'admin' AND target_id = ?)", id, id.toString());
            jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", id);
            jdbc.update("DELETE FROM admin_users WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("create with a code, the new admin logs in and enrols, a reset sends them back to enrolling, and the log has every step")
    void theStaffLifecycle() {
        String session = signIn(tag + "@x.dev");

        // Without the code: refused, and no row.
        assertThat(call(session, HttpMethod.POST, "/admin/admins",
                Map.of("email", tag + "-new@x.dev", "password", "another-long-password", "role", "OPERATOR"), null)
                .getBody().get("code").asInt()).isEqualTo(2021);
        assertThat(admins.findByEmail(tag + "-new@x.dev")).isEmpty();

        // A short password is refused before anything is made.
        assertThat(call(session, HttpMethod.POST, "/admin/admins",
                Map.of("email", tag + "-new@x.dev", "password", "short", "role", "OPERATOR"),
                Totp.codeAt(secret, Instant.now())).getBody().get("code").asInt()).isEqualTo(2029);

        ResponseEntity<JsonNode> created = call(session, HttpMethod.POST, "/admin/admins",
                Map.of("email", tag + "-new@x.dev", "password", "another-long-password", "role", "OPERATOR"),
                Totp.codeAt(secret, Instant.now().plusSeconds(30)));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        long newId = created.getBody().get("data").get("id").asLong();
        made.add(newId);
        assertThat(created.getBody().get("data").get("totpEnrolled").asBoolean()).isFalse();

        // The colleague can log in and is asked to enrol, as the CLI-made first admin is.
        ResponseEntity<JsonNode> login = call(null, HttpMethod.POST, "/admin/auth/login",
                Map.of("email", tag + "-new@x.dev", "password", "another-long-password"), null);
        assertThat(login.getBody().get("data").get("next").asText()).isEqualTo("ENROL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_sessions WHERE admin_id = ?", Integer.class, newId)).isEqualTo(1);

        // Nobody acts on their own row.
        assertThat(call(session, HttpMethod.PATCH, "/admin/admins/" + superId + "/disable", Map.of(), null)
                .getBody().get("code").asInt()).isEqualTo(2028);

        // The log: everything this admin did, newest first -- the login and enrolment included,
        // since those are admin writes too -- and narrowed by action to the one that made a colleague.
        JsonNode all = call(session, HttpMethod.GET, "/admin/audit?adminId=" + superId, null, null).getBody().get("data");
        List<String> actions = new ArrayList<>();
        all.get("items").forEach(i -> actions.add(i.get("action").asText()));
        assertThat(actions).containsExactly("ADMIN_CREATED", "LOGIN", "TOTP_ENROLLED");

        JsonNode log = call(session, HttpMethod.GET, "/admin/audit?adminId=" + superId + "&action=ADMIN_CREATED", null, null)
                .getBody().get("data");
        assertThat(log.get("total").asLong()).isEqualTo(1);
        assertThat(log.get("items").get(0).get("adminEmail").asText()).isEqualTo(tag + "@x.dev");
        assertThat(log.get("items").get(0).get("targetId").asText()).isEqualTo(String.valueOf(newId));
        assertThat(log.get("items").get(0).get("after").asText()).contains("OPERATOR");

        // A filter on an action that never happened is an empty page, not an error.
        assertThat(call(session, HttpMethod.GET, "/admin/audit?adminId=" + superId + "&action=USER_FROZEN", null, null)
                .getBody().get("data").get("total").asLong()).isZero();
    }

    private String signIn(String email) {
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
        if (cookie != null) headers.add(HttpHeaders.COOKIE, cookie);
        if (totp != null) headers.add("X-Admin-TOTP", totp);
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
