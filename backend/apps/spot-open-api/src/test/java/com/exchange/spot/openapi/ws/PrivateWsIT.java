package com.exchange.spot.openapi.ws;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import com.exchange.spot.openapi.common.security.HmacSignature;
import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.apikey.UserApiKeyRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The private half: a signed {@code auth} opens the account's stream, a bad signature is refused
 * with REST's code, a second {@code auth} is 7008, and an acceptance published to the event topic
 * for this user reaches the socket as an {@code orderUpdate} -- published straight to Kafka, so
 * no engine is needed, which is how the stopped-engine CI runs it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=" + PrivateWsIT.ENCRYPTION_KEY,
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class PrivateWsIT {

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
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private UserApiKeyRepository keys;
    @Autowired private KafkaTemplate<String, byte[]> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;
    @Autowired private OpenApiWsHandler handler;

    private User user;
    private UserApiKey key;
    private WebSocketSession session;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() throws Exception {
        user = new User("private-ws-it-" + UUID.randomUUID() + "@example.com", "unused");
        user.setEmailVerified(true);
        user = users.save(user);
        byte[] encrypted = new AesGcmCipher(Base64.getDecoder().decode(ENCRYPTION_KEY)).encrypt(SECRET.getBytes(StandardCharsets.UTF_8));
        key = UserApiKey.hmac(user.getId(), "ws-it", "ak_it_" + UUID.randomUUID(), encrypted, "v1");
        key.setCanRead(true);
        key = keys.save(key);
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                received.add(m.getPayload());
            }
        }, "ws://localhost:" + port + "/v1/ws").get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
        keys.delete(key);
        users.delete(user);
    }

    @Test
    @DisplayName("a bad signature is 2102, a good one opens the stream, and a second auth is 7008")
    void authentication() throws Exception {
        send(auth(1, "wrong-secret"));
        assertThat(await("error").get("code").asInt()).isEqualTo(2102);

        send(auth(2, SECRET));
        JsonNode ok = await("authenticated");
        assertThat(ok.get("id").asInt()).isEqualTo(2);
        assertThat(ok.get("data").get("uid").asLong()).isEqualTo(user.getId());
        assertThat(handler.authenticatedCount(user.getId())).isEqualTo(1);

        send(auth(3, SECRET));
        assertThat(await("error").get("code").asInt()).isEqualTo(7008);
    }

    @Test
    @DisplayName("an acceptance for this account on the event topic reaches the authenticated socket as an orderUpdate")
    void acceptanceReachesTheOwner() throws Exception {
        send(auth(1, SECRET));
        await("authenticated");
        awaitPartitionAssignment();

        UUID orderId = UUID.randomUUID();
        String symbol = "PWS" + (System.nanoTime() % 1_000_000);
        Event event = Event.newBuilder().setSymbol(symbol).setSequence(1).setTimestampUs(Instant.now().toEpochMilli() * 1000)
                .setOrderAccepted(OrderAccepted.newBuilder()
                        .setOrderId(ByteString.copyFrom(Ulid.toBytes(orderId))).setUserId(user.getId())
                        .setSide(Side.SIDE_BUY).setOrderType(OrderType.ORDER_TYPE_LIMIT).setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                        .setPrice(100_00000000L).setQuantity(100_000_000L).setRestingQuantity(100_000_000L))
                .build();
        kafka.send(Topics.SPOT_EVENTS, symbol, event.toByteArray()).get(10, TimeUnit.SECONDS);

        JsonNode frame = await("orderUpdate");
        assertThat(frame.get("data").get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(frame.get("data").get("status").asText()).isEqualTo("NEW");
        assertThat(frame.get("data").get("order").get("price").asText()).isEqualTo("100");
        assertThat(frame.has("code")).isFalse();
    }

    private Map<String, Object> auth(int id, String secret) {
        String ts = String.valueOf(Instant.now().toEpochMilli());
        return Map.of("id", id, "action", "auth", "apiKey", key.getApiKey(), "timestamp", ts,
                "signature", HmacSignature.sign(secret, HmacSignature.canonical(ts, "GET", "/v1/ws", new byte[0])));
    }

    private void send(Map<String, Object> request) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(request)));
    }

    private JsonNode await(String type) throws Exception {
        for (int i = 0; i < 40; i++) {
            String payload = received.poll(10, TimeUnit.SECONDS);
            if (payload == null) {
                break;
            }
            JsonNode frame = json.readTree(payload);
            if (type.equals(frame.path("type").asText())) {
                return frame;
            }
        }
        throw new AssertionError("no " + type + " frame arrived");
    }

    /** The per-instance group starts at latest, so a record published before assignment is never seen. */
    private void awaitPartitionAssignment() throws Exception {
        for (int i = 0; i < 100; i++) {
            boolean assigned = listeners.getListenerContainers().stream()
                    .filter(c -> c.getGroupId() != null && c.getGroupId().contains("user-ws"))
                    .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty());
            if (assigned) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("the user-ws consumer never got a partition");
    }
}
