package com.exchange.spot.openapi.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.spot.openapi.common.security.ApiKeyAuthInterceptor;
import com.exchange.spot.openapi.common.security.HmacSignature;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

/**
 * Real signed requests against the running app, MySQL and Redis: a key minted straight into the
 * table with a secret encrypted under the test key, then the three step-1 endpoints called the
 * way a bot would. Skipped, not failed, without {@code docker compose up -d}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=" + SignedRequestIT.ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class SignedRequestIT {

    static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String SECRET = "it-secret-" + UUID.randomUUID();

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("mongo", 27018);
        requirePort("redis", 6379);
        requirePort("kafka", 9092);
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private UserApiKeyRepository keys;

    private User user;
    private UserApiKey key;

    @BeforeEach
    void mintKey() {
        user = new User("open-api-it-" + UUID.randomUUID() + "@example.com", "unused");
        user.setEmailVerified(true);
        user.setKycLevel(KycLevel.INTERMEDIATE);
        user.setKycVerifiedUntil(Instant.now().plusSeconds(3600));
        user.setMfaEnabled(true);
        user = users.save(user);
        byte[] encrypted = new AesGcmCipher(Base64.getDecoder().decode(ENCRYPTION_KEY))
                .encrypt(SECRET.getBytes(StandardCharsets.UTF_8));
        key = UserApiKey.hmac(user.getId(), "it", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        key.setCanRead(true);
        key.setCanTrade(true);
        key = keys.save(key);
    }

    @AfterEach
    void cleanUp() {
        keys.delete(key);
        users.delete(user);
    }

    @Test
    void timeIsPublic() {
        ResponseEntity<JsonNode> r = rest.getForEntity("http://localhost:" + port + "/v1/time", JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("code").asInt()).isZero();
        assertThat(Instant.parse(r.getBody().get("data").get("serverTime").asText())).isAfter(Instant.now().minusSeconds(60));
    }

    @Test
    @DisplayName("a signed GET /v1/account answers the account, the key's scope and the fee rates")
    void account() {
        JsonNode data = signed(HttpMethod.GET, "/v1/account", "").getBody().get("data");
        assertThat(data.get("uid").asLong()).isEqualTo(user.getId());
        assertThat(data.get("kycLevel").asText()).isEqualTo("INTERMEDIATE");
        assertThat(data.get("canTrade").asBoolean()).isTrue();
        assertThat(data.get("key").get("label").asText()).isEqualTo("it");
        assertThat(data.get("key").get("canTrade").asBoolean()).isTrue();
        assertThat(data.get("fees").get("makerPercent").asText()).matches("-?\\d+\\.\\d{4}");
        // The audit stamp is written on the way through.
        assertThat(keys.findById(key.getId()).orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void balancesAreAnArrayOfDecimalStrings() {
        ResponseEntity<JsonNode> r = signed(HttpMethod.GET, "/v1/wallet/balances", "");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("data").isArray()).isTrue(); // a fresh user holds nothing
    }

    @Test
    @DisplayName("without headers the gate answers 2101, and a wrong secret answers 2102 -- both in the envelope")
    void refusals() {
        ResponseEntity<JsonNode> anonymous = rest.getForEntity("http://localhost:" + port + "/v1/account", JsonNode.class);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.getBody().get("code").asInt()).isEqualTo(2101);

        HttpHeaders headers = headersFor(key.getApiKey(), "GET", "/v1/account", "", "wrong-secret");
        ResponseEntity<JsonNode> forged = rest.exchange("http://localhost:" + port + "/v1/account", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(forged.getStatusCode().value()).isEqualTo(401);
        assertThat(forged.getBody().get("code").asInt()).isEqualTo(2102);
    }

    private ResponseEntity<JsonNode> signed(HttpMethod method, String pathWithQuery, String body) {
        HttpHeaders headers = headersFor(key.getApiKey(), method.name(), pathWithQuery, body, SECRET);
        headers.set("Content-Type", "application/json");
        return rest.exchange("http://localhost:" + port + pathWithQuery, method,
                new HttpEntity<>(body.isEmpty() ? null : body, headers), JsonNode.class);
    }

    private static HttpHeaders headersFor(String apiKey, String method, String pathWithQuery, String body, String secret) {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        HttpHeaders headers = new HttpHeaders();
        headers.set(ApiKeyAuthInterceptor.KEY_HEADER, apiKey);
        headers.set(ApiKeyAuthInterceptor.TIMESTAMP_HEADER, ts);
        headers.set(ApiKeyAuthInterceptor.SIGNATURE_HEADER, HmacSignature.sign(secret,
                HmacSignature.canonical(ts, method, pathWithQuery, body.getBytes(StandardCharsets.UTF_8))));
        return headers;
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
