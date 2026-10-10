package com.exchange.bff.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.identity.UserType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The exchange's own accounts, as V8__system_accounts.sql leaves them
 * (docs/reference/treasury.md). They are ordinary rows in an ordinary table, so what keeps
 * them out of every customer path is worth asserting rather than assuming.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class SystemAccountsIT {

    private static final List<String> ACCOUNTS = List.of("fees@exchange", "treasury@exchange");

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("redis", 6379);
        requirePort("kafka", 9092);
        requirePort("mongodb", 27018);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("both accounts exist, hold no password, and can never open a session")
    void theMigrationLeavesThemUnusable() {
        for (String email : ACCOUNTS) {
            User account = users.findByEmail(email).orElseThrow(() -> new AssertionError("missing " + email));
            assertThat(account.getType()).isEqualTo(UserType.SYSTEM);
            assertThat(account.getPasswordHash()).isNull();
            assertThat(account.isEmailVerified()).isFalse();
            assertThat(account.canLogIn()).isFalse();
        }
    }

    @Test
    @DisplayName("logging in as one answers as if the address did not exist, and mints nothing")
    void loginIsRefusedLikeAnyUnknownAddress() {
        for (String email : ACCOUNTS) {
            ResponseEntity<Map> response = rest.exchange("/bff/auth/login",
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", email, "password", "anything-at-all"), json()),
                    Map.class);

            // 2001 is the same answer a made-up address gets: nothing here confirms the row exists.
            assertThat(response.getBody().get("code")).isEqualTo(2001);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_sessions s JOIN user_users u "
                    + "ON u.id = s.user_id WHERE u.email = ?", Integer.class, email)).isZero();
        }
    }

    @Test
    @DisplayName("a password reset for one sends nothing and creates no code")
    void passwordResetLeavesNoWayIn() {
        for (String email : ACCOUNTS) {
            rest.exchange("/bff/auth/password-reset", org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(Map.of("email", email), json()), Map.class);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_password_reset_codes c "
                    + "JOIN user_users u ON u.id = c.user_id WHERE u.email = ?", Integer.class, email))
                    .isZero();
        }
    }

    private static HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
