package com.exchange.spot.openapi.ws;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The public socket end to end: the shared protocol on this app's path with this app's codes,
 * state channels opening with their state, and the inbound budget refusing a flood.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class OpenApiWsIT {

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
    @Autowired private org.springframework.boot.test.web.client.TestRestTemplate rest;

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
    @DisplayName("depth opens with its state even on an empty book; ticker and kline open with theirs once the market has traded")
    void stateChannelsOpenWithState() throws Exception {
        send(Map.of("id", 1, "action", "subscribe", "channel", "depth", "symbol", "BTCUSDT"));
        JsonNode ack = await("subscribed");
        assertThat(ack.get("id").asInt()).isEqualTo(1);
        assertThat(ack.has("code")).isFalse();
        JsonNode depth = await("depth");
        assertThat(depth.get("data").get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(depth.get("data").get("bids").isArray()).isTrue();

        // A never-traded market (CI's empty Mongo) has no summary and no bar to open with; that
        // absence is the contract, so only a traded market is asked for the opening frames.
        boolean traded = !rest.getForEntity("http://localhost:" + port + "/v1/markets/BTCUSDT/candles?interval=1m&limit=1", JsonNode.class)
                .getBody().get("data").isEmpty();
        send(Map.of("id", 2, "action", "subscribe", "channel", "ticker", "symbol", "BTCUSDT"));
        await("subscribed");
        send(Map.of("id", 3, "action", "subscribe", "channel", "kline", "symbol", "BTCUSDT", "interval", "1m"));
        await("subscribed");
        if (traded) {
            assertThat(await("ticker").get("data").get("lastPrice").asText()).isNotBlank();
            assertThat(await("kline").get("data").get("interval").asText()).isEqualTo("1m");
        }
    }

    @Test
    void protocolErrorsCarryThisAppsCodes() throws Exception {
        send(Map.of("id", 7, "action", "subscribe", "channel", "nope", "symbol", "BTCUSDT"));
        assertThat(await("error").get("code").asInt()).isEqualTo(7005);
        send(Map.of("id", 8, "action", "subscribe", "channel", "kline", "symbol", "BTCUSDT", "interval", "7m"));
        assertThat(await("error").get("code").asInt()).isEqualTo(7006);
        send(Map.of("id", 9, "action", "dance", "symbol", "BTCUSDT"));
        assertThat(await("error").get("code").asInt()).isEqualTo(7003);
    }

    @Test
    @DisplayName("more than ten messages in a second are refused with 7009, and the connection stays open")
    void inboundBudget() throws Exception {
        // The budget is a fixed window on the clock's second (InboundBudget). A burst that
        // straddles a boundary -- nine in one second, six in the next -- is never over ten, so
        // start it at the top of a second: fifteen small frames take milliseconds.
        Thread.sleep(1_000 - System.currentTimeMillis() % 1_000 + 20);
        for (int i = 0; i < 15; i++) {
            send(Map.of("id", 100 + i, "action", "unsubscribe", "channel", "trade", "symbol", "BTCUSDT"));
        }
        boolean refused = false;
        for (int i = 0; i < 15; i++) {
            String payload = received.poll(3, TimeUnit.SECONDS);
            if (payload != null && json.readTree(payload).path("code").asInt() == 7009) {
                refused = true;
            }
        }
        assertThat(refused).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    private void send(Map<String, Object> request) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(request)));
    }

    /** Frames of other types are kept, not dropped: opening frames of two channels arrive in
     *  whichever order their sources answer, and a wait for one must not eat the other. */
    private final java.util.List<JsonNode> unmatched = new java.util.ArrayList<>();

    private JsonNode await(String type) throws Exception {
        for (var it = unmatched.iterator(); it.hasNext();) {
            JsonNode frame = it.next();
            if (type.equals(frame.path("type").asText())) {
                it.remove();
                return frame;
            }
        }
        for (int i = 0; i < 40; i++) {
            String payload = received.poll(5, TimeUnit.SECONDS);
            if (payload == null) {
                break;
            }
            JsonNode frame = json.readTree(payload);
            if (type.equals(frame.path("type").asText())) {
                return frame;
            }
            unmatched.add(frame);
        }
        throw new AssertionError("no " + type + " frame arrived");
    }
}
