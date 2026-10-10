package com.exchange.bff.marketws;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.stream.DepthChannel;
import com.exchange.marketdata.stream.KlineChannel;
import com.exchange.marketdata.stream.MarketWsHandler;
import com.exchange.marketdata.stream.Subscription;
import com.exchange.marketdata.stream.TradeChannel;
import com.exchange.marketdata.depth.DepthBook;
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
import java.util.ArrayList;
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
 * The {@code depth} channel, from a real browser-shaped client.
 *
 * <p>Books are written straight into MongoDB rather than published through Kafka, because
 * {@code market-data} is a different process and is not running here. That is the correct
 * seam anyway: what this app promises is to serve whatever that projection holds, and the
 * fold itself is proved end to end by {@code DepthBuildingIT}.
 *
 * <p>Two of these tests are negative controls, and they are the reason channels exist at
 * all. Before channels a subscription was a symbol, so every subscriber to a market would
 * have received every kind of frame for it -- a chart client parsing order-book updates it
 * never asked for. Asserting that each channel receives <em>only</em> its own frames is
 * what makes the routing meaningful rather than decorative.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA="})
class DepthWsIT {

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

    /**
     * Frames {@link #awaitMessage} read while looking for a different type.
     *
     * <p>It used to drop them, which made this suite quietly lossy in two ways. A test waiting
     * for a second {@code subscribed} ack could discard the first subscription's opening depth
     * frame -- and since the publisher does not resend an unchanged book, that frame never came
     * again, so the test failed intermittently depending on arrival order. Worse, the isolation
     * tests assert that a channel receives <em>nothing</em> it did not ask for, and a leaked
     * frame discarded here would have gone unnoticed: the assertion would pass for the wrong
     * reason. Everything read is kept.
     */
    private final List<String> stashed = new ArrayList<>();
    private WebSocketSession session;

    @AfterEach
    void cleanUp() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.NORMAL);
        }
        for (String id : inserted) {
            mongo.remove(new Query(Criteria.where("_id").is(id)),
                    Market.SPOT.depthCollection());
        }
    }

    /**
     * The whole reason a channel can carry an opening frame. Depth is state, not events: a
     * subscriber who had to wait for the next change would show an empty ladder until then,
     * which on a quiet market is minutes.
     */
    @Test
    @DisplayName("subscribing to depth returns the book immediately, before anything changes")
    void subscribingSendsTheCurrentBookAtOnce() throws Exception {
        String symbol = insertBook("WSDEPTH", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(DepthChannel.NAME, symbol, received);

        JsonNode data = awaitMessage(received, "depth").get("data");
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
        assertThat(data.get("lastSequence").asLong()).isEqualTo(11L);
        assertThat(data.get("bids").get(0).get("price").asText()).isEqualTo("101.25");
        assertThat(data.get("bids").get(0).get("quantity").asText()).isEqualTo("2");
    }

    /** A symbol nobody has ever quoted still gets a frame: a client cannot tell "empty
     *  market" from "still loading" if the server simply says nothing. */
    @Test
    void subscribingToAMarketWithNoBookStillGetsAnEmptyLadder() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(DepthChannel.NAME, "WSDEPTHNONE" + (System.nanoTime() % 1_000_000), received);

        JsonNode data = awaitMessage(received, "depth").get("data");
        assertThat(data.get("bids")).isEmpty();
        assertThat(data.get("asks")).isEmpty();
        assertThat(data.get("lastSequence").asLong()).isZero();
    }

    @Test
    @DisplayName("a book that moves is pushed again within the publish interval")
    void aChangedBookIsPushedAgain() throws Exception {
        String symbol = insertBook("WSDEPTHMOVE", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(DepthChannel.NAME, symbol, received);
        awaitSubscriber(DepthChannel.NAME, symbol);
        assertThat(awaitMessage(received, "depth").get("data").get("lastSequence").asLong())
                .isEqualTo(11L);

        // What market-data's writer does: new quantity, watermark moved forward.
        updateBestBid(symbol, 12, 7 * ONE);

        JsonNode data = awaitFrameAtSequence(received, 12);
        assertThat(data.get("bids").get(0).get("quantity").asText()).isEqualTo("7");
    }

    /** The selector on a trading screen, over the socket: same book, collapsed into
     *  buckets, still a complete refresh so nothing has to be reconciled. */
    @Test
    void aGroupedSubscriptionReceivesBucketedLevels() throws Exception {
        String symbol = insertBook("WSDEPTHGROUP", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        send(Map.of("action", "subscribe", "channel", DepthChannel.NAME,
                "symbol", symbol, "grouping", "1"));
        JsonNode ack = awaitMessage(received, "subscribed").get("data");
        assertThat(ack.get("grouping").asText()).isEqualTo("1");

        JsonNode data = awaitMessage(received, "depth").get("data");
        // 101.25 rounds DOWN to 101 on the bid side; 103 is already on a boundary.
        assertThat(data.get("bids").get(0).get("price").asText()).isEqualTo("101");
        assertThat(data.get("asks").get(0).get("price").asText()).isEqualTo("103");
    }

    /** One market watched at two bucket sizes at once -- a user switching the selector, or
     *  two tabs. Each subscription is its own series and must get its own view. */
    @Test
    void twoGroupingsOfOneMarketEachGetTheirOwnFrames() throws Exception {
        String symbol = insertBook("WSDEPTHTWO", 11, 2 * ONE);
        BlockingQueue<String> coarse = new LinkedBlockingQueue<>();

        session = connect(coarse);
        send(Map.of("action", "subscribe", "channel", DepthChannel.NAME,
                "symbol", symbol, "grouping", "1"));
        awaitMessage(coarse, "subscribed");
        send(Map.of("action", "subscribe", "channel", DepthChannel.NAME,
                "symbol", symbol, "grouping", "10"));
        awaitMessage(coarse, "subscribed");

        List<String> bidPrices = new ArrayList<>();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline) && bidPrices.size() < 2) {
            JsonNode data = awaitMessage(coarse, "depth").get("data");
            String price = data.get("bids").get(0).get("price").asText();
            if (!bidPrices.contains(price)) {
                bidPrices.add(price);
            }
        }
        // 101.25 buckets to 101 at grouping 1, and to 100 at grouping 10.
        assertThat(bidPrices).containsExactlyInAnyOrder("101", "100");
    }

    /** A grouping the market does not offer is refused rather than quietly served: an
     *  arbitrary bucket size can put boundaries mid-tick, which renders a subtly wrong
     *  book instead of failing. */
    @Test
    void aGroupingThisMarketDoesNotOfferIsRefused() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("id", 5, "action", "subscribe", "channel", DepthChannel.NAME,
                "symbol", "BTCUSDT", "grouping", "0.037"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7007);
        assertThat(error.get("id").asInt()).isEqualTo(5);
    }

    /** One a listed market does offer must be accepted -- the negative control above would
     *  also pass against a channel that refused everything. */
    @Test
    void aGroupingThisMarketDoesOfferIsAccepted() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("action", "subscribe", "channel", DepthChannel.NAME,
                "symbol", "BTCUSDT", "grouping", "0.1"));

        assertThat(awaitMessage(received, "subscribed").get("data").get("grouping").asText())
                .isEqualTo("0.1");
    }

    /**
     * A book that is not moving costs nothing. Depth frames are full refreshes, so
     * resending an identical one is pure waste -- and most markets are quiet most of the
     * time, at ten ticks a second each.
     */
    @Test
    void aQuietBookIsNotResentEveryTick() throws Exception {
        String symbol = insertBook("WSDEPTHQUIET", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(DepthChannel.NAME, symbol, received);
        awaitSubscriber(DepthChannel.NAME, symbol);

        // Settle: the opening frame, plus the publisher's own first tick for this symbol.
        Thread.sleep(1_000);
        received.clear();

        // Fifteen publish intervals with nothing changing.
        Thread.sleep(1_500);

        assertThat(typesReceived(received)).doesNotContain("depth");
    }

    /**
     * Negative control. A chart client asked for trades; order-book frames are somebody
     * else's data and would arrive at ten a second on a market it never asked to watch
     * that way.
     */
    @Test
    void aTradeSubscriberIsSentNoDepthFrames() throws Exception {
        String symbol = insertBook("WSDEPTHISOLA", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(TradeChannel.NAME, symbol, received);
        awaitSubscriber(TradeChannel.NAME, symbol);

        // Several publish intervals' worth: a leaking frame has every chance to arrive.
        updateBestBid(symbol, 12, 7 * ONE);
        Thread.sleep(1_500);

        assertThat(typesReceived(received)).doesNotContain("depth");
    }

    /** The other half of the control: depth must not carry trades either. */
    @Test
    void aDepthSubscriberIsNotAlsoSubscribedToTrades() throws Exception {
        String symbol = insertBook("WSDEPTHONLY", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        subscribe(DepthChannel.NAME, symbol, received);
        awaitSubscriber(DepthChannel.NAME, symbol);

        assertThat(handler.subscriberCount(Subscription.of(TradeChannel.NAME, symbol))).isZero();
    }

    /**
     * The trading screen's header, live. Worth an end-to-end test rather than only a unit
     * one because the interesting part is the wiring: that {@code ticker} is a registered
     * channel at all, and that a subscriber is handed the summary without waiting for a
     * trade that may not come for minutes.
     */
    @Test
    void subscribingToTheTickerReturnsTheSummaryAtOnce() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("action", "subscribe", "channel", "ticker", "symbol", "BTCUSDT"));
        assertThat(awaitMessage(received, "subscribed").get("data").get("channel").asText())
                .isEqualTo("ticker");

        JsonNode data = awaitMessage(received, "ticker").get("data");
        assertThat(data.get("symbol").asText()).isEqualTo("BTCUSDT");
        // The fields a client cannot maintain itself: a 24h window slides at both ends.
        assertThat(data.has("volume")).isTrue();
        assertThat(data.has("highPrice")).isTrue();
        assertThat(data.has("priceChangePercent")).isTrue();
        // Money as strings, the rule every view here follows.
        assertThat(data.get("lastPrice").isTextual()).isTrue();
    }

    /** Negative control: a ticker subscriber must not also receive the book. */
    @Test
    void aTickerSubscriberIsSentNoDepthFrames() throws Exception {
        String symbol = insertBook("WSTICKERISO", 11, 2 * ONE);
        BlockingQueue<String> received = new LinkedBlockingQueue<>();

        session = connect(received);
        send(Map.of("action", "subscribe", "channel", "ticker", "symbol", symbol));
        awaitMessage(received, "subscribed");

        updateBestBid(symbol, 12, 7 * ONE);
        Thread.sleep(1_500);

        assertThat(typesReceived(received)).doesNotContain("depth");
    }

    @Test
    void anUnknownChannelIsRefused() throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session = connect(received);

        send(Map.of("id", 9, "action", "subscribe", "channel", "orderbok",
                "symbol", "BTCUSDT"));

        JsonNode error = awaitMessage(received, "error");
        assertThat(error.get("code").asInt()).isEqualTo(7005);
        assertThat(error.get("id").asInt()).isEqualTo(9);
    }

    // ------------------------------------------------------------------ helpers

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

    private void subscribe(String channel, String symbol, BlockingQueue<String> received)
            throws Exception {
        send(Map.of("action", "subscribe", "channel", channel, "symbol", symbol));
        JsonNode data = awaitMessage(received, "subscribed").get("data");
        assertThat(data.get("channel").asText()).isEqualTo(channel);
        assertThat(data.get("symbol").asText()).isEqualTo(symbol);
    }

    private void send(Map<String, Object> request) throws Exception {
        session.sendMessage(new TextMessage(json.writeValueAsString(request)));
    }

    private void awaitSubscriber(String channel, String symbol) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (handler.subscriberCount(Subscription.of(channel, symbol)) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("server never registered a " + channel + " subscriber for "
                + symbol);
    }

    private JsonNode awaitMessage(BlockingQueue<String> received, String type) throws Exception {
        // Anything stashed by an earlier wait may already be the frame being asked for.
        for (java.util.Iterator<String> it = stashed.iterator(); it.hasNext(); ) {
            String payload = it.next();
            JsonNode node = json.readTree(payload);
            if (type.equals(node.path("type").asText())) {
                it.remove();
                return node;
            }
        }
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
            stashed.add(payload);
        }
        throw new AssertionError("no " + type + " frame arrived within " + TIMEOUT);
    }

    /** Skips past frames still carrying the old book -- the publisher may have a tick in
     *  flight when the document changes. */
    private JsonNode awaitFrameAtSequence(BlockingQueue<String> received, long sequence)
            throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            JsonNode data = awaitMessage(received, "depth").get("data");
            if (data.get("lastSequence").asLong() == sequence) {
                return data;
            }
        }
        throw new AssertionError("no depth frame at sequence " + sequence);
    }

    /** Everything seen, stash included -- an isolation assertion that looked only at the queue
     *  could pass because a leaked frame had been set aside rather than because none arrived. */
    private List<String> typesReceived(BlockingQueue<String> received) throws Exception {
        List<String> types = new ArrayList<>();
        for (String payload : stashed) {
            types.add(json.readTree(payload).path("type").asText());
        }
        stashed.clear();
        String payload;
        while ((payload = received.poll()) != null) {
            types.add(json.readTree(payload).path("type").asText());
        }
        return types;
    }

    /** Writes the document shape {@code DepthWriter} produces. */
    private String insertBook(String prefix, long lastSequence, long bestBidQuantity) {
        String symbol = prefix + (System.nanoTime() % 1_000_000);
        String id = DepthBook.idOf(Market.SPOT, symbol);
        inserted.add(id);

        mongo.getCollection(Market.SPOT.depthCollection()).insertOne(new Document("_id", id)
                .append("market", Market.SPOT.name())
                .append("symbol", symbol)
                .append("bids", new Document(String.valueOf(101_25000000L), bestBidQuantity))
                .append("asks", new Document(String.valueOf(103_00000000L), 3 * ONE))
                .append("topBids", List.of(level(101_25000000L, bestBidQuantity)))
                .append("topAsks", List.of(level(103_00000000L, 3 * ONE)))
                .append("lastSequence", lastSequence)
                .append("updatedAt", java.util.Date.from(Instant.now())));
        return symbol;
    }

    private void updateBestBid(String symbol, long lastSequence, long quantity) {
        mongo.getCollection(Market.SPOT.depthCollection()).updateOne(
                new Document("_id", DepthBook.idOf(Market.SPOT, symbol)),
                new Document("$set", new Document("lastSequence", lastSequence)
                        .append("bids", new Document(String.valueOf(101_25000000L), quantity))
                        .append("topBids", List.of(level(101_25000000L, quantity)))));
    }

    private static Document level(long price, long quantity) {
        return new Document("price", price).append("quantity", quantity);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
