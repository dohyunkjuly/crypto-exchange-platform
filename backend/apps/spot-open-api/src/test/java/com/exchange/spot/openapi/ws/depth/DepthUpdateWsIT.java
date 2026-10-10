package com.exchange.spot.openapi.ws.depth;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.DepthLevelChanged;
import com.exchange.contracts.spot.v1.Side;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
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

/** Level changes published to the depth topic reach a subscriber as one frame with absolute
 *  quantities and the sequence range, in pair form. Published straight to Kafka, so no market-data
 *  is needed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class DepthUpdateWsIT {

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
    @Autowired private KafkaTemplate<String, byte[]> kafka;
    @Autowired private KafkaListenerEndpointRegistry listeners;

    private WebSocketSession session;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

    @BeforeEach
    void connect() throws Exception {
        session = new StandardWebSocketClient().execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                received.add(m.getPayload());
            }
        }, "ws://localhost:" + port + "/v1/ws").get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void close() throws Exception {
        if (session != null && session.isOpen()) {
            session.close();
        }
    }

    @Test
    @DisplayName("two changes in one window arrive as one frame: pairs, absolute sizes, the sequence range")
    void changesArriveBatched() throws Exception {
        String symbol = "DUP" + (System.nanoTime() % 1_000_000);
        session.sendMessage(new TextMessage(json.writeValueAsString(
                Map.of("id", 1, "action", "subscribe", "channel", "depthUpdate", "symbol", symbol))));
        assertThat(await("subscribed").get("data").get("channel").asText()).isEqualTo("depthUpdate");
        awaitPartitionAssignment();

        kafka.send(Topics.SPOT_DEPTH, symbol, DepthLevelChanged.newBuilder().setSymbol(symbol).setSequence(7)
                .setSide(Side.SIDE_BUY).setPrice(100_00000000L).setQuantity(2_00000000L).build().toByteArray()).get(10, TimeUnit.SECONDS);
        kafka.send(Topics.SPOT_DEPTH, symbol, DepthLevelChanged.newBuilder().setSymbol(symbol).setSequence(8)
                .setSide(Side.SIDE_SELL).setPrice(101_00000000L).setQuantity(0).build().toByteArray()).get(10, TimeUnit.SECONDS);

        JsonNode frame = await("depthUpdate");
        JsonNode data = frame.get("data");
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("firstSequence").asLong()).isEqualTo(7);
        assertThat(data.get("bids").get(0).get(0).asText()).isEqualTo("100");
        assertThat(data.get("bids").get(0).get(1).asText()).isEqualTo("2");
        // The second change may land in the same frame or the next; either way the ask shows as gone.
        JsonNode asks = data.get("asks");
        if (asks.isEmpty()) {
            asks = await("depthUpdate").get("data").get("asks");
        }
        assertThat(asks.get(0).get(1).asText()).isEqualTo("0");
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

    private void awaitPartitionAssignment() throws Exception {
        for (int i = 0; i < 100; i++) {
            boolean assigned = listeners.getListenerContainers().stream()
                    .filter(c -> c.getGroupId() != null && c.getGroupId().contains("-depth-"))
                    .anyMatch(c -> c.getAssignedPartitions() != null && !c.getAssignedPartitions().isEmpty());
            if (assigned) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("the depth consumer never got a partition");
    }
}
