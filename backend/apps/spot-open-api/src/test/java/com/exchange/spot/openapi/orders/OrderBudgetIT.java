package com.exchange.spot.openapi.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.spot.openapi.common.security.ApiKeyAuthInterceptor;
import com.exchange.spot.openapi.common.security.HmacSignature;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
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
 * The order budget end to end with the ten-second window set to one: the first placement is
 * accepted, the second refused with 3001 and a Retry-After, and a cancel still goes through.
 * Per key, with a freshly minted key, so other suites' traffic in the same Redis cannot bleed in
 * -- the reason the per-IP weight budget is only asserted through its header, in MarketsIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=" + OrderBudgetIT.ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.orders-per-ten-seconds=1"})
class OrderBudgetIT {

    static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String SECRET = "it-secret-" + UUID.randomUUID();

    @BeforeAll
    static void requireInfrastructure() {
        for (var p : new Object[][] {{"mysql", 3308}, {"mongo", 27018}, {"redis", 6379}, {"kafka", 9092}}) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("localhost", (int) p[1]), 500);
            } catch (Exception e) {
                Assumptions.abort(p[0] + " not reachable -- run `docker compose up -d`");
            }
        }
    }

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private UserRepository users;
    @Autowired private UserApiKeyRepository keys;

    private UserApiKey key;

    @AfterEach
    void cleanUp() {
        if (key != null) {
            keys.delete(key);
        }
    }

    @Test
    @DisplayName("the second placement inside the window is 3001 with Retry-After; the cancel that follows is not")
    void secondPlacementIsRefused() {
        User alice = users.findByEmail("alice@example.com").orElse(null);
        Assumptions.assumeTrue(alice != null, "the dev seeder's alice is not present");
        byte[] encrypted = new AesGcmCipher(Base64.getDecoder().decode(ENCRYPTION_KEY)).encrypt(SECRET.getBytes(StandardCharsets.UTF_8));
        key = UserApiKey.hmac(alice.getId(), "budget-it", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        key.setCanRead(true);
        key.setCanTrade(true);
        key = keys.save(key);

        String order = "{\"symbol\":\"BTCUSDT\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"GTC\",\"price\":\"1000.00\",\"quantity\":\"0.02\"}";
        ResponseEntity<JsonNode> first = call(HttpMethod.POST, "/v1/orders", order);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(first.getHeaders().getFirst("X-ORDER-COUNT-10S")).isEqualTo("1");
        assertThat(first.getHeaders().getFirst("X-USED-WEIGHT-1M")).isNotNull();
        String orderId = first.getBody().get("data").get("orderId").asText();

        ResponseEntity<JsonNode> second = call(HttpMethod.POST, "/v1/orders", order);
        assertThat(second.getStatusCode().value()).isEqualTo(429);
        assertThat(second.getBody().get("code").asInt()).isEqualTo(3001);
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();

        ResponseEntity<JsonNode> cancel = call(HttpMethod.DELETE, "/v1/orders/" + orderId, "");
        assertThat(cancel.getStatusCode().value()).isEqualTo(202);
    }

    private ResponseEntity<JsonNode> call(HttpMethod method, String pathWithQuery, String body) {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");
        headers.set(ApiKeyAuthInterceptor.KEY_HEADER, key.getApiKey());
        headers.set(ApiKeyAuthInterceptor.TIMESTAMP_HEADER, ts);
        headers.set(ApiKeyAuthInterceptor.SIGNATURE_HEADER, HmacSignature.sign(SECRET,
                HmacSignature.canonical(ts, method.name(), pathWithQuery, body.getBytes(StandardCharsets.UTF_8))));
        return rest.exchange("http://localhost:" + port + pathWithQuery, method,
                new HttpEntity<>(body.isEmpty() ? null : body, headers), JsonNode.class);
    }
}
