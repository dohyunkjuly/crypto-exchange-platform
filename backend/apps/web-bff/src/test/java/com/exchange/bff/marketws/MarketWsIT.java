package com.exchange.bff.marketws;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.kafka.Topics;
import com.exchange.marketdata.stream.KlineChannel;
import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.marketdata.stream.Subscription;
import com.exchange.marketdata.stream.TradeChannel;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * A real browser-shaped client against a real broker: connect, subscribe, publish a trade
 * to {@code exchange.spot.events.v1}, and assert it arrives on the socket.
 *
 * <p>Worth an IT rather than a unit test because the interesting failures are all at the
 * seams. Whether the per-instance consumer group actually receives every partition,
 * whether {@code auto.offset.reset=latest} takes effect, and whether the handshake gets
 * past the auth interceptor that covers {@code /bff/**} are three things no mock would
 * answer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class MarketWsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("kafka", 9092);
        requirePort("redis", 6379);
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
    private KafkaTemplate<String, byte[]> kafka;

    @Autowired
    private MarketWsHandler handler;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    private final ObjectMapper json = new ObjectMapper();
    private WebSocketSession session;

    /**
     * The socket's consumer starts at {@code latest}, so a message published before this consumer has
     * been assigned its partitions is simply never seen -- assignment lands on the newest
     * offset, which is already past it. Without this wait the first test to run raced
     * group assignment and timed out, while later ones passed. The same window exists in
     * production for a few hundred milliseconds after an instance boots, and is harmless
     * there: a browser gets its history from REST, not from this socket.
     */
    @BeforeEach
    void awaitPartitionAssignment() throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        long assigned = 0;
        while (Instant.now().isBefore(deadline)) {
            assigned = listeners.getListenerContainers().stream()
                    .map(container -> container.getAssignedPartitions())
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(java.util.Collection::size)
                    .sum();
            if (assigned > 0) {
                // Assignment fires before the consumer's first fetch; a beat avoids
                // racing it.
                Thread.sleep(500);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("market WebSocket consumer never took any partition of "
                + Topics.SPOT_EVENTS);
    }

    @AfterEach
    void closeSocket() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.NORMAL);
        }
    }

    @Test
    @DisplayName("a trade published to Kafka reaches a subscribed browser")
    void tradeReachesASubscriber() throws Exception {
        String symbol = "WSIT" + (System.nanoTime() % 1_000_000);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        // No session cookie anywhere in this test: the handshake must not be gated by the
        // auth interceptor, which covers /bff/** for ordinary requests.
        session = connect(received);
        subscribe(symbol, received);

        // The subscription must be registered server-side before publishing -- a live
        // feed pushes what happens next, never what already did.
        awaitSubscriber(symbol);
        long sequence = 4_242L;
        kafka.send(Topics.SPOT_EVENTS, symbol, tradeEvent(symbol, sequence).toByteArray())
                .get(10, TimeUnit.SECONDS);

        JsonNode frame = awaitMessage(received, "trade");
        // A push carries only type + data: no correlation id, and none of REST's
        // code/message ceremony -- see WsMessage for why this is not HttpApiResponse.
        assertThat(frame.has("id")).isFalse();
        assertThat(frame.has("code")).isFalse();
        assertThat(frame.has("message")).isFalse();

        JsonNode trade = frame.get("data");
        assertThat(trade.get("symbol").asText()).isEqualTo(symbol);
        assertThat(trade.get("sequence").asLong()).isEqualTo(sequence);
        assertThat(trade.get("takerSide").asText()).isEqualTo("BUY");
        // Money as decimal strings, never JSON numbers.
        assertThat(trade.get("price").isTextual()).isTrue();
        assertThat(trade.get("price").asText()).isEqualTo("60000.5");
        assertThat(trade.get("quantity").asText()).isEqualTo("0.25");
        assertThat(trade.get("quoteQuantity").asText()).isEqualTo("15000.125");
    }

    @Test
    @DisplayName("a trade in an unsubscribed symbol is not delivered")
    void otherSymbolsAreNotDelivered() throws Exception {
        String mine = "WSA" + (System.nanoTime() % 1_000_000);
        String theirs = "WSB" + (System.nanoTime() % 1_000_000);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(mine, received);
        awaitSubscriber(mine);

        kafka.send(Topics.SPOT_EVENTS, theirs, tradeEvent(theirs, 1L).toByteArray())
                .get(10, TimeUnit.SECONDS);
        // Then one for the subscribed symbol. If the unsubscribed trade were going to
        // arrive it would arrive first -- it was published first, on the same topic.
        kafka.send(Topics.SPOT_EVENTS, mine, tradeEvent(mine, 2L).toByteArray())
                .get(10, TimeUnit.SECONDS);

        JsonNode trade = awaitMessage(received, "trade").get("data");
        assertThat(trade.get("symbol").asText()).isEqualTo(mine);
        assertThat(trade.get("sequence").asLong()).isEqualTo(2L);
    }

    @Test
    @DisplayName("unsubscribing stops delivery and releases the subscription")
    void unsubscribeStopsDelivery() throws Exception {
        String symbol = "WSU" + (System.nanoTime() % 1_000_000);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(symbol, received);
        awaitSubscriber(symbol);

        send(Map.of("action", "unsubscribe", "symbol", symbol));
        assertThat(awaitMessage(received, "unsubscribed").get("data").get("symbol").asText())
                .isEqualTo(symbol);

        assertThat(handler.subscriberCount(Subscription.of(TradeChannel.NAME, symbol))).isZero();
    }

    @Test
    @DisplayName("a malformed request is answered, not fatal to the connection")
    void malformedRequestIsAnswered() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        session.sendMessage(new TextMessage("not json at all"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7001);
        assertThat(error.has("data")).isFalse();
        // Nothing to correlate against: the frame that would have carried an id is the
        // one that failed to parse.
        assertThat(error.has("id")).isFalse();

        // Still usable afterwards -- one bad message must not kill the socket.
        subscribe("WSM" + (System.nanoTime() % 1_000_000), received);
    }

    @Test
    @DisplayName("an unknown action is rejected with its code, echoing the request id")
    void unknownActionIsRejected() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("id", 7, "action", "subscrbe", "symbol", "BTCUSDT"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7003);
        // The whole point of the id: with several requests in flight, this says which one.
        assertThat(error.get("id").asLong()).isEqualTo(7L);
        assertThat(error.get("message").asText()).contains("subscrbe");
    }

    @Test
    @DisplayName("a missing symbol is rejected")
    void missingSymbolIsRejected() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("id", 9, "action", "subscribe"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7002);
        assertThat(error.get("id").asLong()).isEqualTo(9L);
    }

    @Test
    @DisplayName("a reply carries an id only when the request did")
    void repliesCarryNoIdWhenTheRequestHadNone() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("action", "subscribe", "symbol", "WSN" + (System.nanoTime() % 1_000_000)));

        // Omitted, not null: a client testing `"id" in msg` must not find a phantom one.
        assertThat(awaitMessage(received, "subscribed").has("id")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private WebSocketSession connect(BlockingQueue<String> sink) throws Exception {
        // Origin must match the configured allow-list, or the handshake is rejected.
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                        sink.add(m.getPayload());
                    }
                }, headers, URI.create("ws://localhost:" + port + "/bff/ws/market"))
                .get(10, TimeUnit.SECONDS);
    }

    private void subscribe(String symbol, BlockingQueue<String> received) throws Exception {
        send(Map.of("action", "subscribe", "symbol", symbol));
        assertThat(awaitMessage(received, "subscribed").get("data").get("symbol").asText())
                .isEqualTo(symbol);
    }

    private void send(Map<String, Object> request) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(request)));
    }

    /** The subscribe acknowledgement is written before the caller can publish, but this
     *  reads the server's own view rather than trusting message ordering for it. */
    private void awaitSubscriber(String symbol) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (handler.subscriberCount(Subscription.of(TradeChannel.NAME, symbol)) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("server never registered a subscriber for " + symbol);
    }

    private JsonNode awaitMessage(BlockingQueue<String> received, String type) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            String payload = received.poll(500, TimeUnit.MILLISECONDS);
            if (payload == null) {
                continue;
            }
            JsonNode node = json.readTree(payload);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("no '" + type + "' message arrived within " + TIMEOUT);
    }

    private static Event tradeEvent(String symbol, long sequence) {
        return Event.newBuilder()
                .setSymbol(symbol)
                .setSequence(sequence)
                .setTimestampUs(Instant.parse("2026-09-07T12:00:00Z").toEpochMilli() * 1_000)
                .setCommandId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setTradeExecuted(TradeExecuted.newBuilder()
                        .setTradeId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                        .setTakerOrderId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                        .setMakerOrderId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                        .setTakerUserId(1L)
                        .setMakerUserId(2L)
                        .setTakerSide(Side.SIDE_BUY)
                        .setPrice(6_000_050_000_000L)         // 60000.50000000
                        .setQuantity(25_000_000L)             //     0.25000000
                        .setQuoteQuantity(1_500_012_500_000L) // 15000.12500000
                        .setTakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                        .setMakerStatus(OrderStatus.ORDER_STATUS_FILLED))
                .build();
    }
}
