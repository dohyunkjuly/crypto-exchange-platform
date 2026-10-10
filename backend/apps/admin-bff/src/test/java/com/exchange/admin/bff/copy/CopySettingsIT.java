package com.exchange.admin.bff.copy;

import static org.assertj.core.api.Assertions.assertThat;
import com.exchange.admin.identity.AdminRole;
import com.exchange.admin.identity.AdminUser;
import com.exchange.admin.identity.AdminUserRepository;
import com.exchange.common.crypto.Totp;
import com.exchange.copy.settings.CopySettings;
import com.exchange.copy.settings.CopySettingsRepository;
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
 * Copy trading's settings, over HTTP (docs/reference/copy-trading.md): every change needs a
 * fresh code and is audited with the row as it was and as it became.
 *
 * <p>The settings are one row the whole exchange shares, so each test puts it back as it found it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "EXCHANGE_NAME=Example Exchange"})
class CopySettingsIT {

    private static final String PASSWORD = "correct-horse-battery";
    private static final long ONE = 100_000_000L;

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
    @Autowired private CopySettingsRepository settings;
    @Autowired private JdbcTemplate jdbc;

    private final String tag = "settings-it-" + UUID.randomUUID().toString().substring(0, 8);
    private String email;
    private Long adminId;
    private String secret;
    private Map<String, Object> found;

    @BeforeEach
    void seed() {
        email = tag + "@x.dev";
        adminId = admins.save(new AdminUser(email, passwords.encode(PASSWORD), AdminRole.OPERATOR)).getId();
        found = jdbc.queryForMap("SELECT profit_share_bps, failure_limit, max_followers_per_leader, "
                + "max_copies_per_user, min_fixed_ratio_amount, min_fixed_amount, lead_min_balance, "
                + "lead_max_balance, close_sell_threshold FROM copy_settings WHERE id = 1");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("UPDATE copy_settings SET profit_share_bps = ?, failure_limit = ?, "
                        + "max_followers_per_leader = ?, max_copies_per_user = ?, "
                        + "min_fixed_ratio_amount = ?, min_fixed_amount = ?, lead_min_balance = ?, lead_max_balance = ?, "
                        + "close_sell_threshold = ? WHERE id = 1",
                found.values().toArray());
        jdbc.update("DELETE FROM admin_audit_log WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_sessions WHERE admin_id = ?", adminId);
        jdbc.update("DELETE FROM admin_users WHERE id = ?", adminId);
    }

    @Test
    @DisplayName("the settings are read by anyone on staff; a change needs a fresh code, saves the whole row and is "
            + "audited with what it was; a minimum of zero is refused")
    void changeTheSettings() {
        String session = signIn();
        JsonNode before = call(session, HttpMethod.GET, "/admin/copy/settings", null, null).getBody().get("data");
        assertThat(before.get("profitShareBps").asInt()).isEqualTo(((Number) found.get("profit_share_bps")).intValue());
        Map<String, Object> body = new HashMap<>(Map.ofEntries(Map.entry("profitShareBps", 1500),
                Map.entry("failureLimit", 4), Map.entry("maxFollowersPerLeader", 250), Map.entry("maxCopiesPerUser", 8),
                Map.entry("minFixedRatioAmount", "150"), Map.entry("minFixedAmount", "12.5"),
                Map.entry("leadMinBalance", "750"), Map.entry("leadMaxBalance", "2000000"), Map.entry("closeSellThreshold", "5")));

        assertThat(call(session, HttpMethod.PATCH, "/admin/copy/settings", body, null).getBody().get("code").asInt())
                .isEqualTo(2021);
        assertThat(settings.findById(CopySettings.SINGLETON_ID).orElseThrow().getProfitShareBps())
                .as("refused without the code").isEqualTo(before.get("profitShareBps").asInt());

        JsonNode saved = call(session, HttpMethod.PATCH, "/admin/copy/settings", body, Totp.codeAt(secret, Instant.now()))
                .getBody().get("data");
        assertThat(saved.get("profitShareBps").asInt()).isEqualTo(1500);
        assertThat(saved.get("minFixedAmount").asText()).isEqualTo("12.5");
        CopySettings row = settings.findById(CopySettings.SINGLETON_ID).orElseThrow();
        assertThat(row.getFailureLimit()).isEqualTo(4);
        assertThat(row.getLeadMinBalance()).isEqualTo(750 * ONE);
        assertThat(row.getLeadMaxBalance()).isEqualTo(2_000_000 * ONE);
        Map<String, Object> audited = jdbc.queryForMap("SELECT action, before_json, after_json FROM admin_audit_log "
                + "WHERE admin_id = ? AND target_type = 'copy_settings'", adminId);
        assertThat(audited.get("action")).isEqualTo("COPY_SETTINGS_CHANGED");
        assertThat(String.valueOf(audited.get("before_json"))).contains("\"profitShareBps\":" + before.get("profitShareBps").asInt());
        assertThat(String.valueOf(audited.get("after_json"))).contains("\"profitShareBps\":1500").contains("\"leadMinBalance\":\"750\"");

        body.put("minFixedAmount", "0");
        assertThat(call(session, HttpMethod.PATCH, "/admin/copy/settings", body, Totp.codeAt(secret, Instant.now().plusSeconds(30)))
                .getBody().get("code").asInt()).isEqualTo(8015);
        assertThat(settings.findById(CopySettings.SINGLETON_ID).orElseThrow().getMinFixedAmount()).isEqualTo(ONE * 25 / 2);
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
