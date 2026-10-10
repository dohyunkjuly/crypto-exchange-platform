package com.exchange.bff.userws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exchange.common.kafka.Topics;
import com.exchange.copy.leader.CopyLeaderService;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.BalanceChanged;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.FillSettled;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.Settlement;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.bff.testsupport.SeededDataCleaner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * The private order stream, from a real browser-shaped client against a real broker.
 *
 * <p>One test here matters more than the rest: <b>a connection must never receive another
 * user's events</b>. Every instance of this app reads every order on the topic, so the only
 * thing standing between one user and another's order flow is the routing in
 * {@code UserWsHandler}. That is a breach rather than a glitch, and it would look like
 * nothing at all from the inside -- frames arriving, correctly formed, to the wrong person.
 *
 * <p>Skipped, not failed, when the infrastructure is not running.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                "exchange.rate-limit.max-requests=100000"})
class UserWsIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String SYMBOL = "USERWSIT";
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
    private TestRestTemplate rest;

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

    @Autowired
    private UserWsHandler handler;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private CopyLeaderService leaders;

    private final ObjectMapper json = new ObjectMapper();
    private final List<WebSocketSession> sessions = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }
        sessions.clear();
        // Through SeededDataCleaner rather than by hand: a user has rows in half a dozen
        // tables, several with real foreign keys, and it already knows the order.
        for (Long id : createdUserIds) {
            jdbc.update("DELETE FROM copy_leaders WHERE user_id = ?", id);
        }
        new SeededDataCleaner(jdbc).purgeUsers(createdUserIds);
        createdUserIds.clear();
    }

    /**
     * The security assertion. Two users, one order: the other must see nothing at all, not
     * a redacted frame.
     */
    @Test
    @DisplayName("a user is never sent another user's order updates")
    void oneUsersOrdersNeverReachAnother() throws Exception {
        Session mine = signIn();
        Session theirs = signIn();
        BlockingQueue<String> myFrames = connect(mine);
        BlockingQueue<String> theirFrames = connect(theirs);
        awaitConnected(mine.userId());
        awaitConnected(theirs.userId());
        awaitPartitionAssignment();

        publish(accepted(mine.userId(), 1));

        // Mine arrives...
        assertThat(awaitFrame(myFrames).get("data").get("status").asText()).isEqualTo("NEW");
        // ...and theirs stays empty, given time to be wrong in.
        Thread.sleep(1_500);
        assertThat(theirFrames).isEmpty();
    }

    @Test
    @DisplayName("an order accepted by the engine reaches its owner as NEW")
    void anAcceptedOrderReachesItsOwner() throws Exception {
        Session mine = signIn();
        BlockingQueue<String> frames = connect(mine);
        awaitConnected(mine.userId());
        awaitPartitionAssignment();

        UUID orderId = UUID.randomUUID();
        publish(accepted(mine.userId(), 1, orderId));

        JsonNode data = awaitFrame(frames).get("data");
        assertThat(data.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(data.get("symbol").asText()).isEqualTo(SYMBOL);
        assertThat(data.get("status").asText()).isEqualTo("NEW");
        // The whole row rides along, so an open-orders list adds it without a request.
        JsonNode order = data.get("order");
        assertThat(order.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(order.get("orderType").asText()).isEqualTo("LIMIT");
        assertThat(order.get("price").asText()).isEqualTo("100");
        assertThat(order.get("quantity").asText()).isEqualTo("1");
        assertThat(order.get("filledQuantity").asText()).isEqualTo("0");
    }

    @Test
    @DisplayName("a parked stop arrives waiting for its trigger, with what it waits on")
    void aParkedStopArrivesAsPendingTrigger() throws Exception {
        Session mine = signIn();
        BlockingQueue<String> frames = connect(mine);
        awaitConnected(mine.userId());
        awaitPartitionAssignment();

        publish(event(1).setOrderAccepted(OrderAccepted.newBuilder()
                .setOrderId(bytes(UUID.randomUUID()))
                .setUserId(mine.userId())
                .setSide(Side.SIDE_SELL)
                .setOrderType(OrderType.ORDER_TYPE_STOP_LOSS_LIMIT)
                .setPrice(90 * ONE)
                .setStopPrice(95 * ONE)
                .setQuantity(ONE)
                .setRestingQuantity(0)
                .setPendingTrigger(true)).build());

        JsonNode data = awaitFrame(frames).get("data");
        // Not NEW: it is not on the book, and an open-orders row must say so.
        assertThat(data.get("status").asText()).isEqualTo("PENDING_TRIGGER");
        assertThat(data.get("order").get("stopPrice").asText()).isEqualTo("95");
        assertThat(data.get("order").get("triggerDirection").asText()).isEqualTo("FALL");
    }

    @Test
    @DisplayName("an order that filled on arrival comes without a row, since its average price is not known here")
    void aCrossedOrderComesWithoutARow() throws Exception {
        Session mine = signIn();
        BlockingQueue<String> frames = connect(mine);
        awaitConnected(mine.userId());
        awaitPartitionAssignment();

        publish(event(1).setOrderAccepted(OrderAccepted.newBuilder()
                .setOrderId(bytes(UUID.randomUUID()))
                .setUserId(mine.userId())
                .setSide(Side.SIDE_BUY)
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setPrice(100 * ONE)
                .setQuantity(ONE)
                .setRestingQuantity(ONE / 2)).build());

        JsonNode data = awaitFrame(frames).get("data");
        assertThat(data.get("status").asText()).isEqualTo("NEW");
        assertThat(data.get("order") == null || data.get("order").isNull()).isTrue();
    }

    /**
     * Both sides of a fill get a frame, and each describes only their own order. The
     * counterparty's id, status and fee rate are all on the same wire message.
     */
    @Test
    @DisplayName("both sides of a fill are told about their own order and nothing else")
    void aFillReachesBothSidesWithOnlyTheirOwnOrder() throws Exception {
        Session taker = signIn();
        Session maker = signIn();
        BlockingQueue<String> takerFrames = connect(taker);
        BlockingQueue<String> makerFrames = connect(maker);
        awaitConnected(taker.userId());
        awaitConnected(maker.userId());
        awaitPartitionAssignment();

        UUID takerOrder = UUID.randomUUID();
        UUID makerOrder = UUID.randomUUID();
        publish(trade(taker.userId(), maker.userId(), takerOrder, makerOrder));

        JsonNode mineAsTaker = awaitFrame(takerFrames).get("data");
        assertThat(mineAsTaker.get("orderId").asText()).isEqualTo(takerOrder.toString());
        assertThat(mineAsTaker.get("status").asText()).isEqualTo("FILLED");
        assertThat(mineAsTaker.get("remainingQuantity").asText()).isEqualTo("0");

        JsonNode mineAsMaker = awaitFrame(makerFrames).get("data");
        assertThat(mineAsMaker.get("orderId").asText()).isEqualTo(makerOrder.toString());
        assertThat(mineAsMaker.get("status").asText()).isEqualTo("PARTIALLY_FILLED");

        // Neither frame mentions the other's order.
        assertThat(mineAsTaker.toString()).doesNotContain(makerOrder.toString());
        assertThat(mineAsMaker.toString()).doesNotContain(takerOrder.toString());
        // Nor any fee rate: this stream carries order state, not money.
        assertThat(mineAsTaker.toString()).doesNotContain("FeeBps").doesNotContain("fee");
    }

    /**
     * A balance change published by settlement reaches its owner, named and versioned.
     *
     * <p>Published directly here rather than by settling a real trade: that path is proved end to
     * end by {@code WhaleSettlementIT}, and what is under test on this side is the socket.
     */
    @Test
    @DisplayName("a balance change reaches its owner with the asset named and the version intact")
    void abalanceChangeReachesItsOwner() throws Exception {
        Session mine = signIn();
        BlockingQueue<String> frames = connect(mine);
        awaitConnected(mine.userId());
        awaitSettlementAssignment();

        publishSettlement(Settlement.newBuilder()
                .setUserId(mine.userId())
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setBalanceChanged(BalanceChanged.newBuilder()
                        .setAssetId(usdtAssetId()).setFree(900 * ONE).setLocked(100 * ONE)
                        .setVersion(6))
                .build());

        JsonNode data = awaitFrame(frames, "balanceChanged").get("data");
        assertThat(data.get("asset").asText()).isEqualTo("USDT");
        assertThat(data.get("free").asText()).isEqualTo("900");
        assertThat(data.get("version").asLong()).isEqualTo(6L);
        // Money as strings, the rule every view here follows.
        assertThat(data.get("free").isTextual()).isTrue();
    }

    /** The security assertion for this topic: a settlement is one account's money, and nobody
     *  else's socket may see it. */
    @Test
    @DisplayName("a user is never sent another user's balance or fill")
    void oneUsersSettlementsNeverReachAnother() throws Exception {
        Session mine = signIn();
        Session theirs = signIn();
        BlockingQueue<String> myFrames = connect(mine);
        BlockingQueue<String> theirFrames = connect(theirs);
        awaitConnected(mine.userId());
        awaitConnected(theirs.userId());
        awaitSettlementAssignment();

        publishSettlement(Settlement.newBuilder()
                .setUserId(mine.userId())
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setBalanceChanged(BalanceChanged.newBuilder()
                        .setAssetId(usdtAssetId()).setFree(ONE).setVersion(3))
                .build());

        assertThat(awaitFrame(myFrames, "balanceChanged")).isNotNull();
        Thread.sleep(1_500); // time for a leak to show up
        assertThat(theirFrames).isEmpty();
    }

    /** A settled fill, with the fee it actually cost -- negative when the user was the maker. */
    @Test
    void asettledFillReachesItsOwnerWithTheFeeItCost() throws Exception {
        Session mine = signIn();
        BlockingQueue<String> frames = connect(mine);
        awaitConnected(mine.userId());
        awaitSettlementAssignment();

        publishSettlement(Settlement.newBuilder()
                .setUserId(mine.userId())
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setFillSettled(FillSettled.newBuilder()
                        .setTradeId(bytes(UUID.randomUUID()))
                        .setOrderId(bytes(UUID.randomUUID()))
                        .setSymbol("BTCUSDT")
                        .setPrice(100 * ONE).setQuantity(ONE).setQuoteQuantity(100 * ONE)
                        .setSide(com.exchange.contracts.spot.v1.Side.SIDE_SELL)
                        .setIsMaker(true)
                        .setFee(-2_000L)
                        .setFeeAssetId(usdtAssetId()))
                .build());

        JsonNode data = awaitFrame(frames, "fill").get("data");
        assertThat(data.get("symbol").asText()).isEqualTo("BTCUSDT");
        assertThat(data.get("isMaker").asBoolean()).isTrue();
        assertThat(data.get("fee").asText()).startsWith("-");
        assertThat(data.get("feeAsset").asText()).isEqualTo("USDT");
        // No counterparty anywhere: FillSettled has no field for one.
        assertThat(data.has("makerUserId")).isFalse();
        assertThat(data.has("takerUserId")).isFalse();
    }

    /** The handshake is the only point in a socket's life where an HTTP request exists, so
     *  it is the only place the session can be checked at all. */
    @Test
    void anUnauthenticatedHandshakeIsRefused() {
        assertThatThrownBy(() -> open(new WebSocketHttpHeaders(), new LinkedBlockingQueue<>(), ""))
                .hasMessageContaining("401");
    }

    /** A lead account never logs in: its owner watches it from their own session, on a socket
     *  that names it. The routing is by account, so the two must never cross. */
    @Test
    @DisplayName("a socket that names the lead account carries that account's orders and not the customer's own; "
            + "a customer with no lead account is refused one")
    void theLeadAccountHasASocketOfItsOwn() throws Exception {
        Session mine = signIn();
        long lead = leaders.apply(mine.userId(), "ws " + UUID.randomUUID().toString().substring(0, 13), null,
                "127.0.0.1").getLeadUserId();
        createdUserIds.add(lead);
        BlockingQueue<String> own = connect(mine, "");
        BlockingQueue<String> asLead = connect(mine, "?account=lead");
        awaitConnected(mine.userId());
        awaitConnected(lead);
        awaitPartitionAssignment();

        UUID leadOrder = UUID.randomUUID();
        publish(accepted(lead, 1, leadOrder));
        assertThat(awaitFrame(asLead).get("data").get("orderId").asText()).isEqualTo(leadOrder.toString());
        Thread.sleep(1_500);
        assertThat(own).as("the customer's own socket").isEmpty();

        UUID ownOrder = UUID.randomUUID();
        publish(accepted(mine.userId(), 2, ownOrder));
        assertThat(awaitFrame(own).get("data").get("orderId").asText()).isEqualTo(ownOrder.toString());
        Thread.sleep(1_500);
        assertThat(asLead).as("the lead account's socket").isEmpty();

        Session stranger = signIn();
        assertThatThrownBy(() -> connect(stranger, "?account=lead")).hasMessageContaining("403");
        assertThatThrownBy(() -> connect(stranger, "?account=" + lead)).hasMessageContaining("403");
    }


    /** USDT's id, so the frame can be asserted against a real asset name rather than a fixture
     *  -- the cache this exercises is populated from wallet_assets. */
    private long usdtAssetId() {
        return assets.findBySymbol("USDT").orElseThrow().getId();
    }

    private void publishSettlement(Settlement settlement) throws Exception {
        kafka.send(com.exchange.common.kafka.Topics.SPOT_SETTLEMENTS,
                Long.toString(settlement.getUserId()), settlement.toByteArray())
                .get(10, TimeUnit.SECONDS);
    }

    /** The settlements listener is a second container with its own group; `latest` means a
     *  message published before IT has partitions is never seen, however long the events
     *  listener has been ready. */
    private void awaitSettlementAssignment() throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            boolean ready = listeners.getListenerContainers().stream()
                    .filter(c -> c.getContainerProperties().getTopics() != null
                            && java.util.Arrays.asList(c.getContainerProperties().getTopics())
                                    .contains(com.exchange.common.kafka.Topics.SPOT_SETTLEMENTS))
                    .anyMatch(c -> c.getAssignedPartitions() != null
                            && !c.getAssignedPartitions().isEmpty());
            if (ready) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the settlements listener never took a partition");
    }

    /** Keeps frames of other types rather than discarding them -- the isolation assertions read
     *  everything that arrived, and a leak thrown away here would pass for the wrong reason. */
    private JsonNode awaitFrame(BlockingQueue<String> received, String type) throws Exception {
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

    // ------------------------------------------------------------------ helpers

    private record Session(HttpHeaders headers, long userId) {
    }

    private Session signIn() {
        String email = "userws-it-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery";

        ResponseEntity<Map> registered = rest.postForEntity(url("/bff/auth/register"),
                body(Map.of("email", email, "password", password, "name", "IT")), Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> loggedIn = rest.postForEntity(url("/bff/auth/login"),
                body(Map.of("email", email, "password", password)), Map.class);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE,
                loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE).get(0).split(";", 2)[0]);

        Map<String, Object> me = (Map<String, Object>) rest.exchange(
                RequestEntity.get(URI.create(url("/bff/auth/me"))).headers(headers).build(),
                Map.class).getBody().get("data");
        long userId = ((Number) me.get("id")).longValue();
        createdUserIds.add(userId);
        return new Session(headers, userId);
    }

    private BlockingQueue<String> connect(Session session) throws Exception {
        return connect(session, "");
    }

    /** @param query "" for the customer's own socket, or the account it names */
    private BlockingQueue<String> connect(Session session, String query) throws Exception {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://localhost:3000");
        headers.add(HttpHeaders.COOKIE, session.headers().getFirst(HttpHeaders.COOKIE));
        sessions.add(open(headers, received, query));
        return received;
    }

    private WebSocketSession open(WebSocketHttpHeaders headers, BlockingQueue<String> sink, String query)
            throws Exception {
        return new StandardWebSocketClient()
                .execute(new TextWebSocketHandler() {
                    @Override
                    protected void handleTextMessage(WebSocketSession s, TextMessage m) {
                        sink.add(m.getPayload());
                    }
                }, headers, URI.create("ws://localhost:" + port + "/bff/ws/user" + query))
                .get(10, TimeUnit.SECONDS);
    }

    /** The server's own view, rather than trusting that the upgrade finished. */
    private void awaitConnected(long userId) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (handler.connectionCount(userId) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("server never registered a connection for user " + userId);
    }

    /** {@code latest} is resolved at assignment, so an event published before the consumer
     *  owns its partitions is never seen -- the same wait MarketWsIT needs. */
    private void awaitPartitionAssignment() throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            long assigned = listeners.getListenerContainers().stream()
                    .map(container -> container.getAssignedPartitions())
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(java.util.Collection::size)
                    .sum();
            if (assigned > 0) {
                Thread.sleep(500);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("no consumer took any partition of " + Topics.SPOT_EVENTS);
    }

    private JsonNode awaitFrame(BlockingQueue<String> received) throws Exception {
        String payload = received.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        assertThat(payload).as("no orderUpdate frame arrived").isNotNull();
        JsonNode node = json.readTree(payload);
        assertThat(node.get("type").asText()).isEqualTo("orderUpdate");
        return node;
    }

    private void publish(Event event) throws Exception {
        kafka.send(Topics.SPOT_EVENTS, SYMBOL, event.toByteArray()).get(10, TimeUnit.SECONDS);
    }

    private static Event accepted(long userId, long sequence) {
        return accepted(userId, sequence, UUID.randomUUID());
    }

    private static Event accepted(long userId, long sequence, UUID orderId) {
        return event(sequence).setOrderAccepted(OrderAccepted.newBuilder()
                .setOrderId(bytes(orderId))
                .setUserId(userId)
                .setSide(Side.SIDE_BUY)
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setPrice(100 * ONE)
                .setQuantity(ONE)
                .setRestingQuantity(ONE)).build();
    }

    private static Event trade(long takerUserId, long makerUserId, UUID takerOrder,
                               UUID makerOrder) {
        return event(2).setTradeExecuted(TradeExecuted.newBuilder()
                .setTradeId(bytes(UUID.randomUUID()))
                .setTakerOrderId(bytes(takerOrder))
                .setMakerOrderId(bytes(makerOrder))
                .setTakerUserId(takerUserId)
                .setMakerUserId(makerUserId)
                .setTakerSide(Side.SIDE_BUY)
                .setPrice(100 * ONE)
                .setQuantity(ONE)
                .setQuoteQuantity(100 * ONE)
                .setTakerFeeBps(10)
                .setMakerFeeBps(-2)
                .setTakerRemainingQuantity(0)
                .setMakerRemainingQuantity(2 * ONE)
                .setTakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                .setMakerStatus(OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)).build();
    }

    private static Event.Builder event(long sequence) {
        return Event.newBuilder()
                .setSymbol(SYMBOL)
                .setSequence(sequence)
                .setTimestampUs(System.currentTimeMillis() * 1_000);
    }

    private static ByteString bytes(UUID id) {
        return ByteString.copyFrom(Ulid.toBytes(id));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static org.springframework.http.HttpEntity<Map<String, Object>> body(
            Map<String, Object> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(payload, headers);
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }
}
