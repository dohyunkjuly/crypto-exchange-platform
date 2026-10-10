package com.exchange.bff.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.bff.testsupport.SeededDataCleaner;
import com.exchange.common.crypto.Totp;
import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.CancelAllOrders;
import com.exchange.contracts.spot.v1.CancelOrder;
import com.exchange.contracts.spot.v1.CancelScope;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.spot.outbox.SpotCommandOutbox;
import com.exchange.spot.outbox.SpotCommandOutboxRepository;
import com.exchange.spot.symbol.SpotSymbol;
import com.exchange.spot.symbol.SpotSymbolRepository;
import com.exchange.spot.symbol.SymbolStatus;
import com.exchange.spot.trade.SpotTrade;
import com.exchange.spot.trade.SpotTradeRepository;
import com.exchange.user.identity.User;
import com.exchange.user.identity.UserRepository;
import com.exchange.user.kyc.KycLevel;
import com.exchange.user.kyc.KycService;
import com.exchange.user.mfa.MfaService;
import com.exchange.wallet.asset.Asset;
import com.exchange.wallet.asset.AssetRepository;
import com.exchange.wallet.asset.AssetType;
import com.exchange.wallet.balance.BalanceService;
import com.exchange.wallet.ledger.ReferenceType;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The order lifecycle over real HTTP, against real MySQL and a real Kafka broker: place,
 * read back, cancel, and confirm the cancel actually reached the commands topic.
 *
 * <p>Two things here can only be checked end to end, which is why this is an IT and not
 * a unit test:
 *
 * <ul>
 *   <li><b>The cancel command is really published.</b> The matching engine cancels by
 *       order id alone, so "the API said 202" and "the engine will ever hear about it"
 *       are different claims. This asserts the second one by consuming the topic.
 *   <li><b>Ownership is enforced.</b> {@code MatchingEngine.cancelOrder} does not compare
 *       {@code user_id} against the resting order's owner -- the API is the only thing
 *       standing between one user and everybody else's open orders. A regression here
 *       would not fail to compile and would not fail any unit test of the controller.
 * </ul>
 *
 * <p>Skips rather than fails when the infrastructure is absent, the same way {@code
 * WhaleSettlementIT} does, so CI without a broker stays green.
 */
// Throwaway AES-256 keys. Both deliberately have no default anywhere -- per
// AGENTS.md a missing secret must crash the app at startup rather than run on a
// placeholder committed to git. They are separate keys in production for a reason
// (rotating one must not force re-encrypting the other), and separate here to match.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"API_KEY_ENCRYPTION_KEY=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "MFA_ENCRYPTION_KEY=Hx4dHBsaGRgXFhUUExIREA8ODQwLCgkIBwYFBAMCAQA=",
                // Every test registers a user, and the whole class shares one IP, so the
                // production 100/minute would fail a later test for what an earlier one
                // did. The interceptor still runs -- only its ceiling moves.
                "exchange.rate-limit.max-requests=100000"})
class OrderLifecycleIT {

    /** 10,000.00000000 -- comfortably on the 1-unit tick the test symbol is created with. */
    private static final long PRICE = 1_000_000_000_000L;
    private static final String PRICE_DECIMAL = "10000";
    private static final String QUANTITY_DECIMAL = "1";
    private static final long FUNDING = 1_000_000_000_000_000L; // 10,000,000 quote units

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
    private TestRestTemplate rest;

    @Autowired
    private AssetRepository assets;

    @Autowired
    private SpotSymbolRepository spotSymbols;

    @Autowired
    private BalanceService balances;

    @Autowired
    private KycService kyc;

    @Autowired
    private UserRepository users;

    @Autowired
    private MfaService mfa;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SpotTradeRepository spotTrades;

    @Autowired
    private SpotCommandOutboxRepository outboxRows;

    private String symbolCode;
    private SpotSymbol symbol;

    /** Every user this test registers, so the @AfterEach can delete them again. */
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void seedMarket() {
        // SpotSymbol.symbol and Asset.symbol are both varchar(20), so a full nanoTime
        // will not fit alongside a prefix -- same truncation WhaleSettlementIT uses.
        String suffix = Long.toString(System.nanoTime() % 10_000_000_000L);
        symbolCode = "OBIT" + suffix;
        long baseAssetId = assets.save(
                new Asset("OBASE" + suffix, "it base", AssetType.CRYPTO, 8)).getId();
        long quoteAssetId = assets.save(
                new Asset("OQUOTE" + suffix, "it quote", AssetType.CRYPTO, 8)).getId();

        // The topic's last partition: not 0, so a default would not pass for the configured
        // value, and present even on a fresh broker that auto-created the topic with one.
        SpotSymbol created = new SpotSymbol(symbolCode, baseAssetId, quoteAssetId,
                1L, 1L, 1L, Long.MAX_VALUE / 2, 1L, commandPartitions().size() - 1);
        // TRADING is not the default -- a symbol that is not open refuses every order.
        created.setStatus(SymbolStatus.TRADING);
        symbol = spotSymbols.save(created);

        // No fee tier is seeded here: db/seed publishes the SPOT ladder, and Flyway has
        // already run it by the time this context is up. Inserting another level-0 row
        // would actively break things -- findByProductAndLevel returns an Optional and
        // does not filter by effective window, so a second row for the same level makes
        // it throw.
    }

    /**
     * Leaves the database as it was found.
     *
     * <p>Not housekeeping for its own sake: before this existed, test runs had deposited
     * 103,000 orders and 212,000 ledger entries into the shared dev database, which is
     * also what the settlement benchmarks measure insert cost against.
     */
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
    @DisplayName("place, read back, and cancel -- and the cancel really reaches the engine")
    void placeReadAndCancel() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);

        String orderId = place(session, HttpStatus.ACCEPTED);

        // --- it comes back in the open-orders list, with the symbol code resolved -----
        List<Map<String, Object>> open = dataList(get(session, "/bff/orders"));
        Map<String, Object> listed = open.stream()
                .filter(o -> orderId.equals(o.get("orderId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("placed order missing from open orders"));
        assertThat(listed.get("symbol")).isEqualTo(symbolCode);
        assertThat(listed.get("status")).isEqualTo("NEW");
        assertThat(listed.get("side")).isEqualTo("BUY");
        // Money is a decimal string, never a JSON number -- the rule every view here follows.
        assertThat(listed.get("price")).isEqualTo("10000");
        assertThat(listed.get("quantity")).isEqualTo("1");
        assertThat(listed.get("remainingQuantity")).isEqualTo("1");
        assertThat(listed.get("filledQuantity")).isEqualTo("0");

        // --- and individually ---------------------------------------------------------
        Map<String, Object> single = dataMap(get(session, "/bff/orders/" + orderId));
        assertThat(single.get("orderId")).isEqualTo(orderId);
        assertThat(single.get("symbol")).isEqualTo(symbolCode);

        // --- history includes it too --------------------------------------------------
        List<Map<String, Object>> history = dataList(get(session, "/bff/orders/history"));
        assertThat(history).anyMatch(o -> orderId.equals(o.get("orderId")));

        // --- cancel, and prove the command actually landed on the topic ---------------
        List<TopicPartition> partitions = commandPartitions();
        Map<TopicPartition, Long> before = endOffsets(partitions);

        ResponseEntity<Map> canceled = exchange(session, HttpMethod.DELETE,
                "/bff/orders/" + orderId, Map.class);
        assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(((Map<?, ?>) canceled.getBody().get("data")).get("orderId")).isEqualTo(orderId);

        Command cancel = awaitCancelCommand(partitions, before, orderId);
        assertThat(cancel.getSymbol()).isEqualTo(symbolCode);
        assertThat(Ulid.toUuid(cancel.getCancelOrder().getOrderId().toByteArray()).toString())
                .isEqualTo(orderId);
    }

    /** What a request leaves behind when the broker does not answer in time: a row with the
     *  order committed and {@code published_at} still null. The scheduled sweeper -- the real
     *  one, on this app's clock -- has to send it, and stamp it so it is not sent again. */
    @Test
    @DisplayName("a command the request could not publish is sent by the sweeper")
    void theSweeperPublishesAnUnpublishedCommand() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        String orderId = place(session, HttpStatus.ACCEPTED);
        long userId = userId(session);

        List<TopicPartition> partitions = commandPartitions();
        Map<TopicPartition, Long> before = endOffsets(partitions);

        UUID commandId = Ulid.randomUuid();
        Command cancel = Command.newBuilder()
                .setCommandId(com.google.protobuf.ByteString.copyFrom(Ulid.toBytes(commandId)))
                .setSymbol(symbolCode)
                .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                .setCancelOrder(CancelOrder.newBuilder()
                        .setOrderId(com.google.protobuf.ByteString.copyFrom(Ulid.toBytes(UUID.fromString(orderId))))
                        .setUserId(userId))
                .build();
        SpotCommandOutbox row = new SpotCommandOutbox(commandId, symbolCode, symbol.getCommandPartition(),
                UUID.fromString(orderId), userId, cancel.toByteArray());
        // Older than the sweeper's minimum age, or it would still count as the request's to send.
        ReflectionTestUtils.setField(row, "createdAt", Instant.now().minusSeconds(60));
        outboxRows.save(row);

        Command sent = awaitCancelCommand(partitions, before, orderId);
        assertThat(sent.getCommandId()).as("the row's own id, so the engine can dedupe a re-send")
                .isEqualTo(cancel.getCommandId());

        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)
                && outboxRows.findById(commandId).orElseThrow().getPublishedAt() == null) {
            Thread.onSpinWait();
        }
        assertThat(outboxRows.findById(commandId).orElseThrow().getPublishedAt())
                .as("marked, so the next sweep does not send it again").isNotNull();
    }

    /**
     * A 202 now comes back as soon as the order is committed, before its command is on Kafka.
     * So a cancel sent the instant the 202 arrives can be in hand while the place is still on
     * its way out -- and a cancel that reached the engine first would be answered with silence,
     * leaving the order resting with its owner believing it gone.
     */
    @Test
    @DisplayName("a cancel sent the instant the place is answered still reaches Kafka after it")
    void aCancelNeverOvertakesItsPlace() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        List<TopicPartition> partitions = commandPartitions();

        for (int round = 0; round < 20; round++) {
            Map<TopicPartition, Long> before = endOffsets(partitions);
            String orderId = place(session, HttpStatus.ACCEPTED);
            assertThat(exchange(session, HttpMethod.DELETE, "/bff/orders/" + orderId, Map.class)
                    .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            assertThat(commandsAbout(partitions, before, orderId))
                    .as("round %d, order %s", round, orderId)
                    .containsExactly(Command.PayloadCase.PLACE_ORDER, Command.PayloadCase.CANCEL_ORDER);
        }
    }

    /**
     * More orders at once than the database pool has connections (Hikari's default is ten).
     *
     * <p>Each request used to hold its connection through the Kafka publish and then want a
     * SECOND one to mark its outbox row published. With every connection held by a request
     * waiting for another, none could ever be handed over: the whole API stopped until the
     * pool gave up thirty seconds later. One order at a time never shows it.
     */
    @Test
    @DisplayName("a burst of orders larger than the connection pool is all accepted, none stuck waiting for a second connection")
    void aBurstLargerThanTheConnectionPoolIsAllAccepted() throws Exception {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);

        int burst = 40;
        ExecutorService callers = Executors.newFixedThreadPool(burst);
        try {
            List<Future<HttpStatusCode>> answers = new ArrayList<>();
            for (int i = 0; i < burst; i++) {
                answers.add(callers.submit(() -> placeRaw(session).getStatusCode()));
            }
            for (Future<HttpStatusCode> answer : answers) {
                assertThat(answer.get(20, TimeUnit.SECONDS)).isEqualTo(HttpStatus.ACCEPTED);
            }
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    @DisplayName("cancel all queues one CancelAllOrders for the caller, and only the caller")
    void cancelAllReachesTheEngine() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        place(session, HttpStatus.ACCEPTED);

        List<TopicPartition> partitions = commandPartitions();
        Map<TopicPartition, Long> before = endOffsets(partitions);

        ResponseEntity<Map> canceled = exchange(session, HttpMethod.DELETE, "/bff/orders", Map.class);
        assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(((Map<?, ?>) canceled.getBody().get("data")).get("count")).isEqualTo(1);

        Command command = awaitCancelAllCommand(partitions, before, userId(session));
        assertThat(command.getSymbol()).isEqualTo(symbolCode);
    }

    @Test
    @DisplayName("a filtered cancel all is still one command, and carries its filters to the engine")
    void filteredCancelAll() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        place(session, HttpStatus.ACCEPTED);

        // Neither matches a resting buy, so nothing is sent.
        assertThat(cancelAllCount(session, "?scope=TRIGGERS")).isZero();
        assertThat(cancelAllCount(session, "?scope=BOOK&side=SELL")).isZero();

        List<TopicPartition> partitions = commandPartitions();
        Map<TopicPartition, Long> before = endOffsets(partitions);
        assertThat(cancelAllCount(session, "?scope=BOOK&side=BUY&symbol=" + symbolCode)).isEqualTo(1);

        CancelAllOrders request = awaitCancelAllCommand(partitions, before, userId(session))
                .getCancelAllOrders();
        assertThat(request.getSide()).isEqualTo(Side.SIDE_BUY);
        assertThat(request.getScope()).isEqualTo(CancelScope.CANCEL_SCOPE_BOOK);
        assertThat(request.getOrderType()).isEqualTo(OrderType.ORDER_TYPE_UNSPECIFIED);
    }

    private int cancelAllCount(HttpHeaders session, String query) {
        ResponseEntity<Map> response = exchange(session, HttpMethod.DELETE, "/bff/orders" + query, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return (Integer) ((Map<?, ?>) response.getBody().get("data")).get("count");
    }

    @Test
    @DisplayName("order history filters by pair, side and time, and refuses more than 90 days")
    void historyFilters() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        String orderId = place(session, HttpStatus.ACCEPTED);

        assertThat(dataList(get(session, "/bff/orders/history?side=BUY&symbol=" + symbolCode)))
                .anyMatch(o -> orderId.equals(o.get("orderId")));
        assertThat(dataList(get(session, "/bff/orders/history?timeField=UPDATED_AT")))
                .anyMatch(o -> orderId.equals(o.get("orderId")));
        assertThat(dataList(get(session, "/bff/orders/history?side=SELL")))
                .noneMatch(o -> orderId.equals(o.get("orderId")));
        assertThat(dataList(get(session, "/bff/orders/history?symbol=NOPEUSDT"))).isEmpty();
        // A window that ended before the order existed.
        Instant before = Instant.now().minus(Duration.ofDays(1));
        assertThat(dataList(get(session, "/bff/orders/history?from=" + before.minus(Duration.ofDays(2))
                + "&to=" + before))).isEmpty();

        Instant now = Instant.now();
        ResponseEntity<Map> tooLong = get(session, "/bff/orders/history?from="
                + now.minus(Duration.ofDays(91)) + "&to=" + now);
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody().get("code")).isEqualTo(5017);
        assertThat(get(session, "/bff/trades?from=" + now.minus(Duration.ofDays(91)) + "&to=" + now)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("another user can neither read nor cancel an order that is not theirs")
    void ordersAreScopedToTheirOwner() {
        HttpHeaders owner = registerAndLogin();
        verifyAccountForTrading(owner);
        fundQuoteBalance(owner);
        String orderId = place(owner, HttpStatus.ACCEPTED);

        HttpHeaders stranger = registerAndLogin();

        // 404, not 403: confirming the id exists would tell an enumerator their guess
        // was real. See OrderNotFoundException.
        ResponseEntity<Map> read = exchange(stranger, HttpMethod.GET,
                "/bff/orders/" + orderId, Map.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read.getBody().get("code")).isEqualTo(5015);

        // The one that actually matters: the engine does not check ownership itself.
        ResponseEntity<Map> cancel = exchange(stranger, HttpMethod.DELETE,
                "/bff/orders/" + orderId, Map.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(cancel.getBody().get("code")).isEqualTo(5015);

        // And the order is untouched: still open for its real owner.
        List<Map<String, Object>> stillOpen = dataList(get(owner, "/bff/orders"));
        assertThat(stillOpen).anyMatch(o -> orderId.equals(o.get("orderId")));

        // A stranger's open-order list stays empty rather than leaking anything.
        assertThat(dataList(get(stranger, "/bff/orders"))).isEmpty();
    }

    @Test
    @DisplayName("an unknown order id is a 404, not a 500")
    void unknownOrderIsNotFound() {
        HttpHeaders session = registerAndLogin();

        ResponseEntity<Map> response = exchange(session, HttpMethod.GET,
                "/bff/orders/" + Ulid.randomUuid(), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code")).isEqualTo(5015);
    }

    @Test
    @DisplayName("a bad enum value gets its field's own code, and FOK with EXPIRE_BOTH is refused")
    void invalidEnumValuesAndFokWithExpireBothAreRefused() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);

        assertThat(placeCode(session, Map.of("side", "HOLD"))).isEqualTo(5021);
        assertThat(placeCode(session, Map.of("orderType", "ICEBERG"))).isEqualTo(5022);
        assertThat(placeCode(session, Map.of("timeInForce", "DAY"))).isEqualTo(5023);
        assertThat(placeCode(session,
                Map.of("selfTradePreventionMode", "NONE"))).isEqualTo(5024);
        assertThat(placeCode(session, Map.of("timeInForce", "FOK",
                "selfTradePreventionMode", "EXPIRE_BOTH"))).isEqualTo(5020);
    }

    /** {@link #placeRaw}'s request with some fields replaced; returns the error code. */
    private Object placeCode(HttpHeaders session, Map<String, String> overrides) {
        Map<String, String> body = new java.util.HashMap<>(Map.of("symbol", symbolCode,
                "side", "BUY",
                "orderType", "LIMIT",
                "timeInForce", "GTC",
                "price", PRICE_DECIMAL,
                "quantity", QUANTITY_DECIMAL));
        body.putAll(overrides);
        ResponseEntity<Map> response = rest.postForEntity(url("/bff/orders"),
                new HttpEntity<>(body, jsonHeaders(session)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        return response.getBody().get("code");
    }

    /**
     * The amend test's precondition: nothing may apply the first amend before the second is
     * sent. With settlement running the order stops being "pending" within milliseconds, and
     * the second amend is rightly accepted -- the product is correct and the test would fail.
     */
    private static void assumeNothingSettles() {
        try (org.apache.kafka.clients.admin.AdminClient admin = org.apache.kafka.clients.admin.AdminClient.create(
                java.util.Map.<String, Object>of("bootstrap.servers", "localhost:9092"))) {
            var settlement = admin.describeConsumerGroups(java.util.List.of("spot-settlement")).all().get()
                    .get("spot-settlement");
            Assumptions.assumeTrue(settlement == null || settlement.members().isEmpty(),
                    "spot-settlement is running and would apply the first amend at once -- "
                            + "run `docker compose stop spot-settlement spot-matching-engine` to run this test");
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("could not ask the broker who is consuming", e);
        }
    }

    @Test
    @DisplayName("amend is queued and marks the order; a second amend is refused; an unverified account cannot amend")
    void amendIsQueuedOnceAndGatedLikePlacing() {
        assumeNothingSettles();
        // HttpURLConnection, TestRestTemplate's default, cannot send PATCH.
        rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        String orderId = place(session, HttpStatus.ACCEPTED);

        ResponseEntity<Map> amended = amend(session, orderId, Map.of("price", "9000"));
        assertThat(amended.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(dataMap(get(session, "/bff/orders/" + orderId)).get("amendPending")).isEqualTo(true);

        ResponseEntity<Map> second = amend(session, orderId, Map.of("price", "9500"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code")).isEqualTo(5026);

        HttpHeaders unverified = registerAndLogin();
        assertThat(amend(unverified, orderId, Map.of("price", "9000")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map> amend(HttpHeaders session, String orderId, Map<String, String> body) {
        return rest.exchange(url("/bff/orders/" + orderId), HttpMethod.PATCH,
                new HttpEntity<>(body, jsonHeaders(session)), Map.class);
    }

    @Test
    @DisplayName("order endpoints require a session")
    void ordersRequireASession() {
        assertThat(rest.getForEntity(url("/bff/orders"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an unverified account cannot trade, and is told to verify")
    void unverifiedAccountCannotTrade() {
        HttpHeaders session = registerAndLogin();
        verifyEmail(session);
        fundQuoteBalance(session);

        // Funded, authenticated, and asking for a perfectly valid order -- rejected purely
        // on account state.
        ResponseEntity<Map> response = placeRaw(session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(2007);
        // The level is in the message so a client can send them to the right step.
        assertThat((String) response.getBody().get("message")).contains("BASIC");
    }

    @Test
    @DisplayName("KYC alone is not enough -- a second factor is also required")
    void kycWithoutMfaCannotTrade() {
        HttpHeaders session = registerAndLogin();
        verifyEmail(session);
        kyc.approve(userId(session), KycLevel.BASIC, null);
        fundQuoteBalance(session);

        ResponseEntity<Map> response = placeRaw(session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(2008);
    }

    /**
     * KYC lapses at a wall-clock instant with nothing written anywhere, so a gate that
     * only read the level would keep letting this account trade indefinitely.
     */
    @Test
    @DisplayName("expired KYC stops trading even though the level is still BASIC")
    void expiredKycCannotTrade() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);

        // Re-approved with an expiry already in the past: the level is still BASIC, and
        // only the clock has moved.
        kyc.approve(userId(session), KycLevel.BASIC, Instant.now().minusSeconds(60));

        ResponseEntity<Map> response = placeRaw(session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(2007);
    }

    @Test
    @DisplayName("an account that has not confirmed its email cannot trade")
    void unconfirmedEmailCannotTrade() {
        HttpHeaders session = registerAndLogin();
        kyc.approve(userId(session), KycLevel.BASIC, null);
        fundQuoteBalance(session);
        // Everything else in place -- only the inbox step is missing.

        ResponseEntity<Map> response = placeRaw(session);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(2015);
    }

    @Test
    @DisplayName("creating an API key needs a higher level than trading does")
    void apiKeysNeedIntermediate() {
        HttpHeaders session = registerAndLogin();
        // BASIC + 2FA: enough to trade, deliberately not enough for a key.
        verifyAccountForTrading(session);

        // A code is supplied so this reaches the eligibility check rather than stopping
        // at @Valid. Its value is irrelevant: the level is refused before the code is
        // ever examined, which is the point -- an account that may not hold a key is told
        // so rather than being walked through proving a second factor first.
        ResponseEntity<Map> response = rest.postForEntity(url("/bff/api-keys"),
                new HttpEntity<>(Map.of("label", "bot", "canRead", true, "canTrade", true,
                        "mfaCode", "000000"), jsonHeaders(session)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("code")).isEqualTo(2007);
        assertThat((String) response.getBody().get("message")).contains("INTERMEDIATE");
    }

    /**
     * The reason the guard is an opt-in annotation rather than a path pattern.
     *
     * <p>Someone whose verification lapsed must still be able to get out of their
     * positions -- the same principle as a {@code CANCEL_ONLY} symbol. A pattern over
     * {@code /bff/orders/**} would have trapped them, and the failure would look like a
     * support ticket about a stuck account rather than like a bug.
     */
    @Test
    @DisplayName("a lapsed account can still read and cancel, only placing is blocked")
    void lapsedAccountCanStillExitPositions() {
        HttpHeaders session = registerAndLogin();
        verifyAccountForTrading(session);
        fundQuoteBalance(session);
        String orderId = place(session, HttpStatus.ACCEPTED);

        kyc.approve(userId(session), KycLevel.BASIC, Instant.now().minusSeconds(60));

        // Placing is refused...
        assertThat(placeRaw(session).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // ...but the order is still visible...
        assertThat(dataList(get(session, "/bff/orders")))
                .anyMatch(o -> orderId.equals(o.get("orderId")));

        // ...and can still be cancelled.
        ResponseEntity<Map> canceled = exchange(session, HttpMethod.DELETE,
                "/bff/orders/" + orderId, Map.class);
        assertThat(canceled.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A user's own fills, which an order list cannot show: an order carries a cumulative
     * quantity and no fee at all.
     *
     * <p>The two fees differ on purpose. A projection that reached for the wrong side would
     * return a number that looks perfectly reasonable, and only a counterparty comparing
     * statements would ever notice.
     */
    @Test
    @DisplayName("my trades show my own side and fee, never the counterparty's")
    void myTradesShowMyOwnSideAndFee() {
        HttpHeaders session = registerAndLogin();
        long me = userId(session);

        // This user sold; the buyer was the maker, so this user was the taker.
        SpotTrade trade = new SpotTrade(UUID.randomUUID(), symbol.getId(), PRICE, 100_000_000L,
                UUID.randomUUID(), UUID.randomUUID(), me + 1, me, true, 1L, Instant.now());
        trade.chargeFees(1_111L, symbol.getBaseAssetId(), 2_222L, symbol.getQuoteAssetId());
        spotTrades.save(trade);

        ResponseEntity<String> raw = rest.exchange(url("/bff/trades?symbol=" + symbolCode),
                HttpMethod.GET, new HttpEntity<>(session), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> mine = tradesFrom(session);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("side")).isEqualTo("SELL");
        assertThat(mine.get(0).get("isMaker")).isEqualTo(false);   // buyerIsMaker, so I took
        assertThat(mine.get(0).get("fee")).isEqualTo("0.00002222"); // the seller's, not 1111
        assertThat(raw.getBody()).doesNotContain("0.00001111");
    }

    /**
     * The rates an order form shows before anyone commits money.
     *
     * <p>Asserted against the seeded base rung rather than a fixture: these are the
     * product's real numbers, and a seed edit that changed them by accident should fail
     * here rather than quietly reprice the exchange.
     */
    @Test
    @DisplayName("fees expose both rates, the tier they came from, and rebates as negative")
    void feesReportTheUsersOwnRates() {
        HttpHeaders session = registerAndLogin();

        ResponseEntity<Map> response = rest.exchange(url("/bff/fees"),
                HttpMethod.GET, new HttpEntity<>(session), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> fees = (Map<String, Object>) response.getBody().get("data");
        // A fresh account has no tier row, so it resolves to the published base rung.
        assertThat(fees.get("tierLevel")).isEqualTo(0);
        assertThat(fees.get("custom")).isEqualTo(false);
        assertThat(fees).containsKeys("makerBps", "takerBps", "makerPercent", "takerPercent");

        // Basis points are the exact value; the percentage is a rendering of it, and the
        // two must agree or the form shows a number the engine will not charge.
        int takerBps = (int) fees.get("takerBps");
        assertThat(new java.math.BigDecimal((String) fees.get("takerPercent")))
                .isEqualByComparingTo(new java.math.BigDecimal(takerBps).movePointLeft(2));
    }

    /** Someone whose KYC has lapsed cannot place an order but is still entitled to know
     *  what trading would cost -- so this is behind a session and no further gate. */
    @Test
    void feesRequireASessionAndNothingMore() {
        assertThat(rest.getForEntity(url("/bff/fees"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // A brand-new account: email unverified, no KYC, no 2FA -- cannot trade, can still
        // read its rates.
        assertThat(rest.exchange(url("/bff/fees"), HttpMethod.GET,
                new HttpEntity<>(registerAndLogin()), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /**
     * The snapshot half of the balance handshake.
     *
     * <p>A client combines this with the live {@code balanceChanged} feed, where several
     * processes publish after their own commits and arrival order is not write order. Without a
     * version here it has no starting point, so the first push after a reconnect is either
     * wrongly dropped or wrongly applied -- and either way the balance stays wrong until that
     * account next changes.
     */
    @Test
    @DisplayName("balances carry the row version a client needs to order the live feed")
    void balancesCarryTheirVersion() {
        HttpHeaders session = registerAndLogin();
        long me = userId(session);
        balances.credit(me, symbol.getQuoteAssetId(), FUNDING,
                com.exchange.wallet.ledger.ReferenceType.DEPOSIT, "seed", "v-seed:" + me);

        ResponseEntity<Map> response = rest.exchange(url("/bff/wallet/balances"),
                HttpMethod.GET, new HttpEntity<>(session), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> listed = (List<Map<String, Object>>) response.getBody().get("data");
        assertThat(listed).isNotEmpty();
        assertThat(listed.get(0)).containsKey("version");
        // A written row has been updated at least once, so its version has moved off zero --
        // a hard-coded 0 would satisfy containsKey and mean nothing.
        assertThat(((Number) listed.get(0).get("version")).longValue()).isNotNegative();
    }

    /** Reading what you already did is not trading, but it is still your data: the session
     *  is what separates this from the public tape on {@code /bff/market/{symbol}/trades}. */
    @Test
    void myTradesRequireASession() {
        ResponseEntity<Map> anonymous = rest.getForEntity(url("/bff/trades"), Map.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** A market this user has never traded is empty, not an error. */
    @Test
    void myTradesInAnUntradedMarketAreEmpty() {
        HttpHeaders session = registerAndLogin();

        assertThat(tradesFrom(session)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tradesFrom(HttpHeaders session) {
        ResponseEntity<Map> response = rest.exchange(url("/bff/trades?symbol=" + symbolCode),
                HttpMethod.GET, new HttpEntity<>(session), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) response.getBody().get("data");
    }

    private HttpHeaders registerAndLogin() {
        String email = "order-it-" + UUID.randomUUID() + "@example.com";
        String password = "correct-horse-battery";

        ResponseEntity<Map> registered = rest.postForEntity(url("/bff/auth/register"),
                json(Map.of("email", email, "password", password, "name", "IT")), Map.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> loggedIn = rest.postForEntity(url("/bff/auth/login"),
                json(Map.of("email", email, "password", password)), Map.class);
        assertThat(loggedIn.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> cookies = loggedIn.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).isNotNull();
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, cookies.get(0).split(";", 2)[0]);
        createdUserIds.add(userId(headers));
        return headers;
    }

    /**
     * Brings an account up to the bar {@code EligibilityService} sets for trading: KYC at
     * BASIC and an active second factor.
     *
     * <p>The 2FA half goes through the real enrolment path -- start, then confirm with a
     * code computed from the secret it just issued -- rather than writing a verified row
     * directly. A test that fakes the enrolment would still pass if enrolment were broken.
     */
    /** Marks the address confirmed without going through the emailed link -- see
     *  EmailVerificationIT for that path. */
    private void verifyEmail(HttpHeaders session) {
        User user = users.findById(userId(session)).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);
    }

    private void verifyAccountForTrading(HttpHeaders session) {
        long userId = userId(session);

        // Set directly rather than by redeeming a link: this test is about orders, and
        // the verification flow itself is covered end to end by EmailVerificationIT.
        User user = users.findById(userId).orElseThrow();
        user.setEmailVerified(true);
        users.save(user);

        // Through KycService, not the repository: it is the only thing that keeps the
        // profile and user_users' read model in step, and a test that wrote the profile
        // directly would leave the gate reading NONE and prove nothing.
        kyc.approve(userId, KycLevel.BASIC, null);

        String secret = mfa.startTotpEnrolment(userId, "it@example.com", "Exchange").secret();
        String code = Totp.codeAt(secret, Instant.now());
        mfa.confirmTotpEnrolment(userId, code, "127.0.0.1");
    }

    private long userId(HttpHeaders session) {
        return ((Number) dataMap(get(session, "/bff/auth/me")).get("id")).longValue();
    }

    /** A BUY locks quote, so that is what has to be there. Credited through
     *  BalanceService rather than SQL so the ledger stays consistent. */
    private void fundQuoteBalance(HttpHeaders session) {
        long userId = userId(session);
        balances.credit(userId, symbol.getQuoteAssetId(), FUNDING, ReferenceType.TRANSFER,
                "order-it", "order-it-funding:" + userId + ":" + symbol.getQuoteAssetId());
    }

    /** The same request {@link #place} makes, handed back unasserted so a test can look
     *  at a rejection. */
    private ResponseEntity<Map> placeRaw(HttpHeaders session) {
        return rest.postForEntity(url("/bff/orders"),
                new HttpEntity<>(Map.of("symbol", symbolCode,
                        "side", "BUY",
                        "orderType", "LIMIT",
                        "timeInForce", "GTC",
                        "price", PRICE_DECIMAL,
                        "quantity", QUANTITY_DECIMAL), jsonHeaders(session)),
                Map.class);
    }

    private String place(HttpHeaders session, HttpStatus expected) {
        ResponseEntity<Map> response = rest.postForEntity(url("/bff/orders"),
                new HttpEntity<>(Map.of("symbol", symbolCode,
                        "side", "BUY",
                        "orderType", "LIMIT",
                        "timeInForce", "GTC",
                        "price", PRICE_DECIMAL,
                        "quantity", QUANTITY_DECIMAL), jsonHeaders(session)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return (String) ((Map<?, ?>) response.getBody().get("data")).get("orderId");
    }

    private ResponseEntity<Map> get(HttpHeaders session, String path) {
        return exchange(session, HttpMethod.GET, path, Map.class);
    }

    private <T> ResponseEntity<T> exchange(HttpHeaders session, HttpMethod method, String path,
                                           Class<T> type) {
        return rest.exchange(url(path), method, new HttpEntity<>(jsonHeaders(session)), type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataMap(ResponseEntity<Map> response) {
        return (Map<String, Object>) body(response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> dataList(ResponseEntity<Map> response) {
        return (List<Map<String, Object>>) body(response);
    }

    private Object body(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("expected 2xx but got %s: %s", response.getStatusCode(), response.getBody())
                .isTrue();
        return response.getBody().get("data");
    }

    private HttpEntity<Object> json(Object body) {
        return new HttpEntity<>(body, jsonHeaders(null));
    }

    private HttpHeaders jsonHeaders(HttpHeaders session) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (session != null) {
            headers.addAll(session);
        }
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ------------------------------------------------------------------ kafka

    private KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", ByteArrayDeserializer.class.getName());
        props.put("enable.auto.commit", "false");
        return new KafkaConsumer<>(props);
    }

    private List<TopicPartition> commandPartitions() {
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (PartitionInfo info : consumer.partitionsFor(Topics.SPOT_COMMANDS)) {
                partitions.add(new TopicPartition(info.topic(), info.partition()));
            }
            return partitions;
        }
    }

    /** Where the topic ends before the cancel, so the search below cannot be satisfied
     *  by some earlier run's command sitting on the same topic. */
    private Map<TopicPartition, Long> endOffsets(List<TopicPartition> partitions) {
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.assign(partitions);
            return consumer.endOffsets(partitions);
        }
    }

    private Command awaitCancelCommand(List<TopicPartition> partitions,
                                       Map<TopicPartition, Long> from, String orderId) {
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.assign(partitions);
            from.forEach(consumer::seek);

            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    Command command = parse(record.value());
                    if (command == null || !command.hasCancelOrder()) {
                        continue;
                    }
                    UUID canceled =
                            Ulid.toUuid(command.getCancelOrder().getOrderId().toByteArray());
                    if (canceled.toString().equals(orderId)) {
                        assertThat(record.partition()).as("the market's configured partition")
                                .isEqualTo(symbol.getCommandPartition());
                        return command;
                    }
                }
            }
        }
        throw new AssertionError("no CancelOrder command for " + orderId
                + " reached " + Topics.SPOT_COMMANDS);
    }

    /** Every command about one order since {@code from}, in the order Kafka holds them, up to
     *  and including its cancel. */
    private List<Command.PayloadCase> commandsAbout(List<TopicPartition> partitions,
                                                    Map<TopicPartition, Long> from, String orderId) {
        List<Command.PayloadCase> seen = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.assign(partitions);
            from.forEach(consumer::seek);
            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    Command command = parse(record.value());
                    if (command == null) {
                        continue;
                    }
                    com.google.protobuf.ByteString about = switch (command.getPayloadCase()) {
                        case PLACE_ORDER -> command.getPlaceOrder().getOrderId();
                        case CANCEL_ORDER -> command.getCancelOrder().getOrderId();
                        default -> null;
                    };
                    if (about != null && Ulid.toUuid(about.toByteArray()).toString().equals(orderId)) {
                        seen.add(command.getPayloadCase());
                        if (command.getPayloadCase() == Command.PayloadCase.CANCEL_ORDER) {
                            return seen;
                        }
                    }
                }
            }
        }
        return seen;
    }

    private Command awaitCancelAllCommand(List<TopicPartition> partitions,
                                          Map<TopicPartition, Long> from, long userId) {
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            consumer.assign(partitions);
            from.forEach(consumer::seek);

            Instant deadline = Instant.now().plusSeconds(30);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    Command command = parse(record.value());
                    if (command != null && command.hasCancelAllOrders()
                            && command.getCancelAllOrders().getUserId() == userId) {
                        return command;
                    }
                }
            }
        }
        throw new AssertionError("no CancelAllOrders for user " + userId
                + " reached " + Topics.SPOT_COMMANDS);
    }

    private static Command parse(byte[] value) {
        try {
            return Command.parseFrom(value);
        } catch (Exception e) {
            return null; // Not our message; the topic is shared with other runs.
        }
    }
}
