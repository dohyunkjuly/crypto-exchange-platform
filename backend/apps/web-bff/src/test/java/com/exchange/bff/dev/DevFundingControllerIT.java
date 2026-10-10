package com.exchange.bff.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.money.Money;
import com.exchange.user.identity.UserRepository;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The dev faucet: it funds a named account, once per reference, and refuses nonsense. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
@ActiveProfiles("dev")
class DevFundingControllerIT {

    @BeforeAll
    static void requireInfrastructure() {
        for (int port : new int[] {3308, 6379, 9092, 27018}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", port), 500);
            } catch (Exception e) {
                Assumptions.abort("port " + port + " not reachable -- run `docker compose up -d`");
            }
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository users;
    @Autowired
    private JdbcTemplate jdbc;

    /** Every user a test signed up, so the @AfterEach can delete them again. */
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void removeSignedUpUsers() {
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** A registered account, unverified and with no second factor: funding is not gated. */
    private String signUp() {
        String email = "faucet-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = rest.exchange(url("/bff/auth/register"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "password", "faucet-dev-password"), json()),
                Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        createdUserIds.add(users.findByEmail(email).orElseThrow().getId());
        return email;
    }

    private ResponseEntity<Map> fund(String email, String amount, String reference) {
        return rest.exchange(url("/dev/fund"), HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "asset", "USDT", "amount", amount,
                        "reference", reference), json()),
                Map.class);
    }

    @Test
    @DisplayName("credits the account it is given, and says what it now holds")
    void fundsTheNamedAccount() {
        String email = signUp();

        ResponseEntity<Map> funded = fund(email, "1500.25", "first");

        assertThat(funded.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> balance = (Map<?, ?>) funded.getBody().get("data");
        assertThat(balance.get("asset")).isEqualTo("USDT");
        assertThat(balance.get("available")).isEqualTo("1500.25");
        // Ledgered as what it stands in for. As a TRADE_CREDIT it would count as trade proceeds
        // in anything that reads the ledger by type.
        assertThat(jdbc.queryForObject("SELECT entry_type FROM wallet_ledger_entries WHERE reference_id = 'dev-faucet' "
                + "AND user_id = (SELECT id FROM user_users WHERE email = ?)", String.class, email)).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("the same reference credits once, so a provisioning script can run twice")
    void theSameReferenceCreditsOnce() {
        String email = signUp();

        fund(email, "1000", "seed");
        ResponseEntity<Map> again = fund(email, "1000", "seed");

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) again.getBody().get("data")).get("available")).isEqualTo("1000");
    }

    @Test
    @DisplayName("a different reference credits again")
    void aDifferentReferenceCreditsAgain() {
        String email = signUp();

        fund(email, "1000", "one");
        ResponseEntity<Map> second = fund(email, "1000", "two");

        assertThat(((Map<?, ?>) second.getBody().get("data")).get("available")).isEqualTo("2000");
    }

    @Test
    @DisplayName("refuses an amount that is not money, or is more than the cap")
    void refusesNonsense() {
        String email = signUp();
        String overCap = Money.toDecimal(DevFundingController.MAX_PER_CALL + Money.SCALE_FACTOR);

        for (String amount : List.of("0", "-5", "abc", "0.000000001", overCap)) {
            assertThat(fund(email, amount, "bad-" + amount).getStatusCode())
                    .as("amount %s", amount)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    @DisplayName("refuses an account that does not exist, rather than inventing one")
    void refusesAnUnknownAccount() {
        ResponseEntity<Map> refused = fund("nobody-" + UUID.randomUUID() + "@example.com", "1000", "nope");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
