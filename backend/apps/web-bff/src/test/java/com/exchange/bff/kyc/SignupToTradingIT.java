package com.exchange.bff.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.common.mail.Mailer;
import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The whole signup flow, over HTTP, with nothing reached around: register, confirm the
 * emailed link, submit KYC, enrol a factor, log in through the challenge, place an order.
 *
 * <p>Every other test in this app sets some of that up by calling a service directly,
 * which is right for a test about something else. This one exists to prove the path a real
 * person takes connects end to end -- each step was built separately, and "each part
 * works" is not the same claim as "you can get from a signup form to a resting order".
 *
 * <p>The review itself is staff's, on admin-bff, and out of this app's reach: this test stands
 * in for the reviewer by approving through {@code KycService} directly, the way admin-bff does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
@ActiveProfiles("dev")
class SignupToTradingIT {

    private static final String PASSWORD = "correct-horse-battery";

    @TestConfiguration
    static class CapturingMailerConfig {
        @Bean
        @Primary
        CapturingMailer capturingMailer() {
            return new CapturingMailer();
        }
    }

    static class CapturingMailer implements Mailer {
        private final ConcurrentLinkedQueue<String> bodies = new ConcurrentLinkedQueue<>();

        @Override
        public void send(String to, String subject, String body) {
            bodies.add(body);
        }

        String lastBody() {
            String last = null;
            for (String body : bodies) {
                last = body;
            }
            return last;
        }

        void clear() {
            bodies.clear();
        }
    }

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

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private SpotSymbolRepository spotSymbols;

    @Autowired
    private BalanceService balances;

    @Autowired
    private KycService kycService;

    private final List<Long> createdUserIds = new ArrayList<>();
    private SpotSymbol symbol;

    @BeforeEach
    void seedMarket() {
        mailer.clear();
        String suffix = Long.toString(System.nanoTime() % 10_000_000_000L);
        long baseAssetId = assets.save(
                new Asset("SBASE" + suffix, "it base", AssetType.CRYPTO, 8)).getId();
        long quoteAssetId = assets.save(
                new Asset("SQUOTE" + suffix, "it quote", AssetType.CRYPTO, 8)).getId();
        SpotSymbol created = new SpotSymbol("SIGN" + suffix, baseAssetId, quoteAssetId,
                1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, 0);
        created.setStatus(SymbolStatus.TRADING);
        symbol = spotSymbols.save(created);
    }

    @AfterEach
    void removeSeededData() {
        SeededDataCleaner cleaner = new SeededDataCleaner(jdbc);
        cleaner.purgeUsers(createdUserIds);
        createdUserIds.clear();
        if (symbol != null) {
            cleaner.purgeMarket(symbol.getId(), symbol.getBaseAssetId(),
                    symbol.getQuoteAssetId());
        }
    }

    @Test
    @DisplayName("register, verify, KYC, 2FA, log in with a code, place an order")
    void aNewUserCanReachTheOrderBook() {
        // --- 1. register -----------------------------------------------------------
        String email = "signup-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long userId = ((Number) data(registered).get("id")).longValue();
        createdUserIds.add(userId);

        // No second factor yet, so the password alone signs in.
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));
        assertThat(session).isNotNull();

        // --- 2. the emailed code ---------------------------------------------------
        assertThat(post("/bff/auth/verify-email",
                Map.of("code", codeFromLastEmail()), session).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // --- 3. KYC ---------------------------------------------------------------
        assertThat(data(get("/bff/kyc", session)).get("status")).isEqualTo("NOT_STARTED");
        ResponseEntity<Map> submitted = post("/bff/kyc",
                Map.of("legalFirstName", "Ada", "legalLastName", "Lovelace",
                        "dateOfBirth", "1990-12-10",
                        "nationality", "GB", "residenceCountry", "GB"), session);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // Submitting grants nothing: the profile waits for a reviewer, and the customer sees it wait.
        assertThat(data(get("/bff/kyc", session)).get("status")).isEqualTo("PENDING");
        assertThat(data(get("/bff/kyc", session)).get("level")).isEqualTo("NONE");
        // The reviewer approves it -- on admin-bff in life; here the same service call it makes.
        kycService.approve(userIdOf(email), KycLevel.INTERMEDIATE, null);
        assertThat(data(get("/bff/kyc", session)).get("level")).isEqualTo("INTERMEDIATE");

        // --- 4. enrol a second factor ---------------------------------------------
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        assertThat(data(post("/bff/mfa/totp/confirm",
                Map.of("code", Totp.codeAt(secret, Instant.now())), session))
                .get("recoveryCodes")).isNotNull();

        // --- 5. logging in now takes the code -------------------------------------
        ResponseEntity<Map> challenged = post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null);
        assertThat(data(challenged).get("mfaRequired")).isEqualTo(true);
        assertThat(sessionCookie(challenged)).isNull();

        ResponseEntity<Map> completed = post("/bff/auth/login/mfa",
                Map.of("challengeToken", data(challenged).get("challengeToken"),
                        // Not the current code: confirming the enrolment just spent that
                        // step, and a spent step is refused as a replay.
                        "code", Totp.codeAt(secret, Instant.now().plusSeconds(30))), null);
        String tradingSession = sessionCookie(completed);
        assertThat(tradingSession).isNotNull();

        // --- 6. and the account can trade -----------------------------------------
        balances.credit(userId, symbol.getQuoteAssetId(), 1_000_000_000_000_000L,
                ReferenceType.TRANSFER, "signup-it", "signup-it-funding:" + userId);

        ResponseEntity<Map> placed = post("/bff/orders",
                Map.of("symbol", symbol.getSymbol(), "side", "BUY", "orderType", "LIMIT",
                        "timeInForce", "GTC", "price", "10000", "quantity", "1"),
                tradingSession);

        assertThat(placed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(data(placed).get("orderId")).isNotNull();
    }

    /** Locally, the dev approver is the only thing that grants INTERMEDIATE -- the level an API
     *  key needs -- so this is what makes API Management usable in development at all. */
    @Test
    @DisplayName("an account a reviewer approved at INTERMEDIATE can create an API key")
    void reviewedAccountCanCreateAnApiKey() {
        String email = "signup-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        createdUserIds.add(((Number) data(registered).get("id")).longValue());
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));
        post("/bff/auth/verify-email", Map.of("code", codeFromLastEmail()), session);
        post("/bff/kyc", Map.of("legalFirstName", "Ada", "legalLastName", "Lovelace",
                "dateOfBirth", "1990-12-10", "nationality", "GB", "residenceCountry", "GB"), session);
        kycService.approve(userIdOf(email), KycLevel.INTERMEDIATE, null);
        String secret = (String) data(post("/bff/mfa/totp", Map.of(), session)).get("secret");
        post("/bff/mfa/totp/confirm", Map.of("code", Totp.codeAt(secret, Instant.now())), session);

        ResponseEntity<Map> key = post("/bff/api-keys",
                Map.of("label", "signup-it", "canRead", true, "canTrade", true,
                        // Enrolling spent the current step, and a spent step is refused as a replay.
                        "mfaCode", Totp.codeAt(secret, Instant.now().plusSeconds(30))),
                session);

        assertThat(key.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(data(key).get("secret")).isNotNull();
    }

    /** Identity checks start from a mailbox we know the user holds, as every venue
     *  require. The frontend funnels an unverified user to the code; this is the rule. */
    @Test
    @DisplayName("KYC cannot be submitted until the email is verified")
    void kycNeedsAVerifiedEmail() {
        String email = "signup-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        createdUserIds.add(((Number) data(registered).get("id")).longValue());
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));

        ResponseEntity<Map> refused = post("/bff/kyc", Map.of("legalFirstName", "Ada",
                "legalLastName", "Lovelace", "dateOfBirth", "1990-12-10",
                "nationality", "GB", "residenceCountry", "GB"), session);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code")).isEqualTo(2015);
        // Nothing half-written: the profile does not exist, let alone sit PENDING.
        assertThat(data(get("/bff/kyc", session)).get("status")).isEqualTo("NOT_STARTED");
    }

    /** Personal data is shown back to its owner from its own endpoint, and never rides along on
     *  the status that the page polls and the dashboard reads. */
    @Test
    @DisplayName("submitted details come from /kyc/details, never from the status")
    void detailsHaveTheirOwnEndpoint() {
        String email = "signup-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        createdUserIds.add(((Number) data(registered).get("id")).longValue());
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));
        post("/bff/auth/verify-email", Map.of("code", codeFromLastEmail()), session);
        post("/bff/kyc", Map.of("legalFirstName", "Ada", "legalLastName", "Lovelace",
                "dateOfBirth", "1990-12-10", "nationality", "GB", "residenceCountry", "KR"), session);

        ResponseEntity<Map> details = get("/bff/kyc/details", session);
        assertThat(details.getHeaders().getCacheControl()).contains("no-store");
        assertThat(data(details)).containsEntry("legalFirstName", "Ada")
                .containsEntry("legalLastName", "Lovelace")
                .containsEntry("dateOfBirth", "1990-12-10")
                .containsEntry("nationality", "GB")
                .containsEntry("residenceCountry", "KR");

        assertThat(data(get("/bff/kyc", session)))
                .doesNotContainKeys("legalFirstName", "legalLastName", "dateOfBirth",
                        "nationality", "residenceCountry");
    }

    @Test
    @DisplayName("submitting again once verified is refused, not silently reset")
    void resubmittingAfterApprovalIsRefused() {
        String email = "signup-it-" + UUID.randomUUID() + "@example.com";
        ResponseEntity<Map> registered = post("/bff/auth/register",
                Map.of("email", email, "password", PASSWORD, "name", "IT"), null);
        createdUserIds.add(((Number) data(registered).get("id")).longValue());
        String session = sessionCookie(post("/bff/auth/login",
                Map.of("email", email, "password", PASSWORD), null));
        // KYC starts from a confirmed mailbox, so the code comes first here too.
        post("/bff/auth/verify-email", Map.of("code", codeFromLastEmail()), session);

        Map<String, Object> details = Map.of("legalFirstName", "Ada",
                "legalLastName", "Lovelace", "dateOfBirth", "1990-12-10",
                "nationality", "GB", "residenceCountry", "GB");
        post("/bff/kyc", details, session);
        kycService.approve(userIdOf(email), KycLevel.INTERMEDIATE, null);

        // Accepting this would drop an approved account back to PENDING and revoke what it
        // had unlocked -- someone re-checking their details would lose the ability to trade.
        ResponseEntity<Map> again = post("/bff/kyc", details, session);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code")).isEqualTo(2016);
        assertThat(data(get("/bff/kyc", session)).get("level")).isEqualTo("INTERMEDIATE");
    }

    // ------------------------------------------------------------------ helpers

    private String codeFromLastEmail() {
        String body = mailer.lastBody();
        assertThat(body).as("no email was sent").isNotNull();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\b(\\d{6})\\b").matcher(body);
        assertThat(matcher.find()).as("no six-digit code in: %s", body).isTrue();
        return matcher.group(1);
    }

    private ResponseEntity<Map> post(String path, Map<String, ?> payload, String session) {
        return rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(payload, headers(session)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String session) {
        return rest.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers(session)), Map.class);
    }

    private HttpHeaders headers(String session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.add(HttpHeaders.COOKIE, session);
        }
        return headers;
    }

    private String sessionCookie(ResponseEntity<Map> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        return cookies.stream()
                .filter(c -> c.startsWith("session=") && !c.startsWith("session=;"))
                .map(c -> c.split(";", 2)[0])
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return (Map<String, Object>) response.getBody().get("data");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Long userIdOf(String email) {
        return jdbc.queryForObject("SELECT id FROM user_users WHERE email = ?", Long.class, email);
    }
}
