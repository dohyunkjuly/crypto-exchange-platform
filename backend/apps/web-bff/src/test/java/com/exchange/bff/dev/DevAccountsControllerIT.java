package com.exchange.bff.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.fee.FeeProduct;
import com.exchange.fee.FeeQueryService;
import com.exchange.fee.FeeRates;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.wallet.balance.Balance;
import com.exchange.wallet.balance.BalanceService;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** One call, and the accounts can trade. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
@ActiveProfiles("dev")
class DevAccountsControllerIT {

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
    private BalanceService balances;
    @Autowired
    private EligibilityService eligibility;
    @Autowired
    private FeeQueryService fees;
    @Autowired
    private JdbcTemplate jdbc;

    /** Every account a test asked for, so the @AfterEach can delete them again. */
    private final List<Long> createdUserIds = new ArrayList<>();

    /** The endpoint never reuses an account, so a run that kept them would add to the shared dev
     *  database every time: real users, each with a key, a second factor and a funded wallet. */
    @AfterEach
    void removeCreatedAccounts() {
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    private ResponseEntity<Map> create(int count, String fund) {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange("http://localhost:" + port + "/dev/accounts",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("count", count, "label", "it", "fund", fund), json), Map.class);
        if (response.getStatusCode().is2xxSuccessful()) {
            accountsIn(response).forEach(a -> createdUserIds.add(((Number) a.get("userId")).longValue()));
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> accountsIn(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.getBody().get("data")).get("accounts");
    }

    @Test
    @DisplayName("needs no session: a caller asking for its first account has none to offer")
    void needsNoSession() {
        assertThat(create(1, "1000").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("every account it returns may actually trade, is funded, and has a key of its own")
    void theAccountsCanTrade() {
        List<Map<String, Object>> accounts = accountsIn(create(3, "5000"));
        assertThat(accounts).hasSize(3);

        for (Map<String, Object> account : accounts) {
            User created = users.findById(((Number) account.get("userId")).longValue()).orElseThrow();
            // The whole point: the gate spot-open-api enforces on every order says yes.
            assertThatNoException().isThrownBy(() -> eligibility.requireCanTrade(created));

            assertThat((String) account.get("apiKey")).isNotBlank();
            assertThat((String) account.get("apiSecret")).isNotBlank();
            assertThat((String) account.get("totpSecret")).isNotBlank();

            List<Balance> funded = balances.list(created.getId());
            assertThat(funded).isNotEmpty();
            assertThat(funded).allSatisfy(b -> assertThat(b.getAvailable()).isPositive());

            // The bots trade with each other; fees would only drain their funding for nothing.
            assertThat(fees.ratesFor(created.getId(), FeeProduct.SPOT)).isEqualTo(new FeeRates(0, 0));
        }
        assertThat(accounts.stream().map(a -> a.get("apiKey")).distinct()).hasSize(3);
    }

    @Test
    @DisplayName("two calls never collide, because every call creates new accounts")
    void everyCallCreatesNewAccounts() {
        List<Map<String, Object>> first = accountsIn(create(2, "1000"));
        List<Map<String, Object>> second = accountsIn(create(2, "1000"));

        assertThat(first.stream().map(a -> a.get("email")))
                .doesNotContainAnyElementsOf(second.stream().map(a -> a.get("email")).toList());
    }

    @Test
    @DisplayName("refuses a batch or a funding amount that is not sensible")
    void refusesNonsense() {
        assertThat(create(0, "1000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(501, "1000").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(1, "0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(1, "-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(1, "banana").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(create(1, "999999999999").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
