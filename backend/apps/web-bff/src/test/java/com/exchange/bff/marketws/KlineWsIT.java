package com.exchange.bff.marketws;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.stream.DepthChannel;
import com.exchange.marketdata.stream.KlineChannel;
import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.marketdata.stream.Subscription;
import com.exchange.marketdata.stream.TradeChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The {@code kline} channel, from a real browser-shaped client.
 *
 * <p>Candles are written straight into MongoDB, as {@code market-data} would. That the fold
 * itself is right is proved by {@code CandleBuildingIT} in that app; what is under test here
 * is the channel: that a subscriber gets the bar it needs, at the interval it asked for, and
 * nothing else.
 *
 * <p>Skipped, not failed, when the infrastructure is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class KlineWsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final long ONE = 100_000_000L;

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("mysql", 3308);
        requirePort("kafka", 9092);
        requirePort("redis", 6379);
        requirePort("mongodb", 27018);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MongoTemplate mongo;

    @Autowired
    private MarketWsHandler handler;

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> inserted = new ArrayList<>();
    private WebSocketSession session;

    @AfterEach
    void cleanUp() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.NORMAL);
        }
        for (String id : inserted) {
            mongo.remove(new Query(Criteria.where("_id").is(id)),
                    Market.SPOT.candleCollection());
        }
    }

    /** A candle is state, not an event: a chart that had to wait for the next trade would
     *  show a hole where the live bar goes. */
    @Test
    @DisplayName("subscribing returns the bar being drawn, before any trade arrives")
    void subscribingSendsTheCurrentBarAtOnce() throws Exception {
        String symbol = "WSKLINE" + (System.nanoTime() % 1_000_000);
        Instant openTime = openBucket();
        insertCandle(symbol, CandleInterval.ONE_MINUTE, openTime, 7, 100 * ONE, 105 * ONE);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);
        subscribe(symbol, "1m", received);

        JsonNode data = awaitMessage(received, "kline").get("data");
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("interval").asText()).isEqualTo("1m");
        assertThat(data.get("open").asText()).isEqualTo("100");
        assertThat(data.get("close").asText()).isEqualTo("105");
        // Money as strings, the rule every view here follows.
        assertThat(data.get("close").isTextual()).isTrue();
    }

    /** A bucket whose window has passed is final, so a chart can commit it and stop
     *  redrawing. Derived from the clock, since nothing writes a row to say so. */
    @Test
    void aFinishedBucketIsReportedClosed() throws Exception {
        String symbol = "WSKLINEOLD" + (System.nanoTime() % 1_000_000);
        // Two hours back: its minute ended long ago.
        Instant openTime = CandleInterval.ONE_MINUTE.bucketStart(
                Instant.now().minus(Duration.ofHours(2)));
        insertCandle(symbol, CandleInterval.ONE_MINUTE, openTime, 7, 100 * ONE, 105 * ONE);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);
        subscribe(symbol, "1m", received);

        assertThat(awaitMessage(received, "kline").get("data").get("closed").asBoolean())
                .isTrue();
    }

    @Test
    void theBarBeingDrawnIsNotReportedClosed() throws Exception {
        String symbol = "WSKLINELIVE" + (System.nanoTime() % 1_000_000);
        insertCandle(symbol, CandleInterval.ONE_MINUTE, openBucket(), 7, 100 * ONE, 105 * ONE);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);
        subscribe(symbol, "1m", received);

        assertThat(awaitMessage(received, "kline").get("data").get("closed").asBoolean())
                .isFalse();
    }

    /**
     * The whole reason this channel exists rather than leaving clients to fold trades. A
     * frame is the bar's absolute state, so whatever the client missed between fetching
     * history and subscribing is simply overwritten -- no handshake, no gap detection.
     */
    @Test
    @DisplayName("an updated bar is pushed again, replacing what the client held")
    void anUpdatedBarIsPushedAgain() throws Exception {
        String symbol = "WSKLINEMOVE" + (System.nanoTime() % 1_000_000);
        Instant openTime = openBucket();
        insertCandle(symbol, CandleInterval.ONE_MINUTE, openTime, 7, 100 * ONE, 105 * ONE);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);
        subscribe(symbol, "1m", received);
        awaitSubscriber(symbol, "1m");
        awaitMessage(received, "kline");

        // What CandleWriter does on the next trade: new close, watermark moved forward.
        updateCandle(symbol, CandleInterval.ONE_MINUTE, openTime, 9, 130 * ONE);

        JsonNode data = awaitFrameClosing(received, "130");
        assertThat(data.get("openTime").asText())
                .isEqualTo(openTime.toString().replace("Z", "Z"));
    }

    /** Negative control on the interval. One market is commonly watched at several
     *  timeframes at once, and a 1m subscriber must not be sent 1h bars. */
    @Test
    void aSubscriberIsSentOnlyTheIntervalItAskedFor() throws Exception {
        String symbol = "WSKLINEISO" + (System.nanoTime() % 1_000_000);
        Instant minute = openBucket();
        insertCandle(symbol, CandleInterval.ONE_MINUTE, minute, 7, 100 * ONE, 105 * ONE);
        insertCandle(symbol, CandleInterval.ONE_HOUR,
                CandleInterval.ONE_HOUR.bucketStart(Instant.now()), 7, 100 * ONE, 900 * ONE);

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);
        subscribe(symbol, "1m", received);
        awaitSubscriber(symbol, "1m");
        Thread.sleep(2_000); // several publish intervals

        List<String> intervals = new ArrayList<>();
        String payload;
        while ((payload = received.poll()) != null) {
            JsonNode node = json.readTree(payload);
            if ("kline".equals(node.path("type").asText())) {
                intervals.add(node.get("data").get("interval").asText());
            }
        }
        assertThat(intervals).isNotEmpty().containsOnly("1m");
    }

    /** There is no default bar size. Guessing one would draw the wrong chart and say
     *  nothing about it. */
    @Test
    void anUnknownIntervalIsRefused() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("id", 3, "action", "subscribe", "channel", "kline",
                "symbol", "BTCUSDT", "interval", "7s"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7006);
        assertThat(error.get("id").asInt()).isEqualTo(3);
    }

    /** Validation runs before the subscription is recorded, so a refused request leaves
     *  nothing behind for the publisher to poll. */
    @Test
    void aRefusedSubscriptionIsNotRecorded() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("action", "subscribe", "channel", "kline",
                "symbol", "BTCUSDT", "interval", ""));
        awaitMessage(received, "error");

        assertThat(handler.watched(KlineChannel.NAME)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private static Instant openBucket() {
        return CandleInterval.ONE_MINUTE.bucketStart(Instant.now());
    }

    private WebSocketSession connect(BlockingQueue<String> sink) throws Exception {
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

    private void subscribe(String symbol, String interval, BlockingQueue<String> received)
            throws Exception {
        send(Map.of("action", "subscribe", "channel", "kline",
                "symbol", symbol, "interval", interval));
        JsonNode data = awaitMessage(received, "subscribed").get("data");
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("interval").asText()).isEqualTo(interval);
    }

    private void send(Map<String, Object> request) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(request)));
    }

    private void awaitSubscriber(String symbol, String interval) throws Exception {
        Subscription subscription = new Subscription(KlineChannel.NAME, symbol, interval, "");
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (handler.subscriberCount(subscription) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("server never registered " + subscription);
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
        throw new AssertionError("no " + type + " frame arrived within " + TIMEOUT);
    }

    /** Skips frames still carrying the old bar -- a tick may be in flight when the
     *  document changes. */
    private JsonNode awaitFrameClosing(BlockingQueue<String> received, String close)
            throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode data = awaitMessage(received, "kline").get("data");
            if (close.equals(data.get("close").asText())) {
                return data;
            }
        }
        throw new AssertionError("no kline frame closing at " + close);
    }

    private void insertCandle(String symbol, CandleInterval interval, Instant openTime,
                              long lastSequence, long open, long close) {
        String id = Candle.idOf(Market.SPOT, symbol, interval, openTime);
        inserted.add(id);
        mongo.getCollection(Market.SPOT.candleCollection()).insertOne(new Document("_id", id)
                .append("market", Market.SPOT.name())
                .append("symbol", symbol)
                .append("interval", interval.name())
                .append("openTime", java.util.Date.from(openTime))
                .append("open", open)
                .append("high", Math.max(open, close))
                .append("low", Math.min(open, close))
                .append("close", close)
                .append("volume", 5 * ONE)
                .append("quoteVolume", 500 * ONE)
                .append("tradeCount", 3)
                .append("lastSequence", lastSequence)
                .append("updatedAt", java.util.Date.from(Instant.now())));
    }

    private void updateCandle(String symbol, CandleInterval interval, Instant openTime,
                              long lastSequence, long close) {
        mongo.getCollection(Market.SPOT.candleCollection()).updateOne(
                new Document("_id", Candle.idOf(Market.SPOT, symbol, interval, openTime)),
                new Document("$set", new Document("close", close)
                        .append("high", close)
                        .append("lastSequence", lastSequence)));
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
