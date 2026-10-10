package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.kafka.Topics;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.CancelReason;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.contracts.spot.v1.OrderCanceled;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.marketdata.MarketDataApplication;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthBook;
import com.exchange.marketdata.depth.DepthLevel;
import com.exchange.marketdata.depth.DepthRepository;
import com.exchange.marketdata.depth.DepthService;
import com.exchange.marketdata.testsupport.EventsTopic;
import com.exchange.marketdata.testsupport.SeededMarketData;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The end-to-end proof, against a REAL Kafka broker and a REAL MongoDB: order events
 * published to {@code exchange.spot.events.v1} become an L2 book.
 *
 * <p>Three things are worth proving here that a mocked unit test cannot.
 *
 * <ol>
 *   <li><b>The ladder is right</b> after a realistic mix of resting, filling and
 *       cancelling -- the arithmetic and the side routing together, through the real
 *       serialisation, not through a hand-built {@code LevelDelta}.
 *   <li><b>A redelivery changes nothing.</b> Depth is built from signed deltas, so applying
 *       a batch twice leaves levels permanently wrong with no exception and no log line.
 *       Publishing the identical events again and asserting the book did not move is the
 *       only way to know the watermark works end to end.
 *   <li><b>A restart resumes the stored book.</b> This is the claim the whole design rests
 *       on -- that no snapshot machinery is needed because the projection is durable and
 *       advances with the offset. Asserting it against a real Mongo is the difference
 *       between believing it and knowing it.
 * </ol>
 *
 * <p>Skipped, not failed, when the infrastructure is not running -- same convention as
 * {@code CandleBuildingIT}.
 */
class DepthBuildingIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long ONE = 100_000_000L;

    private final List<String> usedSymbols = new ArrayList<>();

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("mongodb", 27018);
        EventsTopic.ensureExists(BOOTSTRAP);
    }

    /** Each test invents its own symbol, so its book is its own document. Removing them
     *  keeps the dev database free of accumulated test debris. */
    @AfterEach
    void removeTestBooks() {
        if (usedSymbols.isEmpty()) {
            return;
        }
        try (ConfigurableApplicationContext ctx = start()) {
            SeededMarketData.purge(ctx, usedSymbols);
        }
    }

    @Test
    void restingFillingAndCancellingBuildTheLadder() throws Exception {
        String symbol = symbol("ITDEPTH");

        try (ConfigurableApplicationContext ctx = start()) {
            DepthService depth = ctx.getBean(DepthService.class);
            EventsTopic.awaitAssignment(ctx, Duration.ofMinutes(1));

            publish(symbol, List.of(
                    accepted(1, Side.SIDE_BUY, 100 * ONE, 5 * ONE),
                    accepted(2, Side.SIDE_BUY, 99 * ONE, 3 * ONE),
                    accepted(3, Side.SIDE_SELL, 101 * ONE, 4 * ONE),
                    // A buying taker consumes the resting ASK, not the bid.
                    trade(4, Side.SIDE_BUY, 101 * ONE, ONE),
                    cancel(5, Side.SIDE_BUY, 100 * ONE, 2 * ONE),
                    // Takes the whole level, which must disappear rather than sit at zero.
                    cancel(6, Side.SIDE_BUY, 99 * ONE, 3 * ONE)));

            await(() -> sequenceReached(depth, symbol, 6));

            Depth book = depth.of(Market.SPOT, symbol, 20).orElseThrow();
            assertThat(book.bids()).containsExactly(new DepthLevel(100 * ONE, 3 * ONE));
            assertThat(book.asks()).containsExactly(new DepthLevel(101 * ONE, 3 * ONE));
            assertThat(book.lastSequence()).isEqualTo(6L);

            // The stored ladder agrees with the precomputed arrays, and the emptied level
            // is gone from it rather than stored as a zero nothing would ever remove.
            DepthBook stored = ctx.getBean(DepthRepository.class)
                    .findFull(Market.SPOT, symbol).orElseThrow();
            assertThat(stored.getBids()).containsExactly(
                    Map.entry(String.valueOf(100 * ONE), 3 * ONE));
            assertThat(stored.getAsks()).containsExactly(
                    Map.entry(String.valueOf(101 * ONE), 3 * ONE));
        }
    }

    @Test
    void republishingTheSameEventsLeavesTheBookUnchanged() throws Exception {
        String symbol = symbol("ITDEPTHREPLAY");
        List<Event> batch = List.of(
                accepted(1, Side.SIDE_BUY, 100 * ONE, 5 * ONE),
                accepted(2, Side.SIDE_SELL, 101 * ONE, 4 * ONE),
                trade(3, Side.SIDE_BUY, 101 * ONE, ONE));

        try (ConfigurableApplicationContext ctx = start()) {
            DepthService depth = ctx.getBean(DepthService.class);
            EventsTopic.awaitAssignment(ctx, Duration.ofMinutes(1));

            publish(symbol, batch);
            await(() -> sequenceReached(depth, symbol, 3));
            Depth first = depth.of(Market.SPOT, symbol, 20).orElseThrow();

            // Exactly what a Kafka redelivery looks like: the same events, again.
            publish(symbol, batch);
            Thread.sleep(3_000); // long enough to genuinely process, and wrongly apply

            Depth after = depth.of(Market.SPOT, symbol, 20).orElseThrow();
            assertThat(after.bids()).isEqualTo(first.bids());
            assertThat(after.asks()).isEqualTo(first.asks());
            assertThat(after.lastSequence()).isEqualTo(3L);
        }
    }

    @Test
    void aRestartResumesTheStoredBookRatherThanStartingEmpty() throws Exception {
        String symbol = symbol("ITDEPTHRESTART");

        try (ConfigurableApplicationContext first = start()) {
            EventsTopic.awaitAssignment(first, Duration.ofMinutes(1));
            publish(symbol, List.of(
                    accepted(1, Side.SIDE_BUY, 100 * ONE, 5 * ONE),
                    accepted(2, Side.SIDE_SELL, 101 * ONE, 4 * ONE)));
            await(() -> sequenceReached(first.getBean(DepthService.class), symbol, 2));
        }

        // A second process, with no memory of anything: everything it knows about this
        // book has to come back out of MongoDB.
        try (ConfigurableApplicationContext restarted = start()) {
            DepthService depth = restarted.getBean(DepthService.class);
            EventsTopic.awaitAssignment(restarted, Duration.ofMinutes(1));

            publish(symbol, List.of(cancel(3, Side.SIDE_BUY, 100 * ONE, 2 * ONE)));
            await(() -> sequenceReached(depth, symbol, 3));

            Depth book = depth.of(Market.SPOT, symbol, 20).orElseThrow();
            // 5 - 2, so the restored level was there to subtract from. A writer that
            // started empty would have written -2, dropped the level, and lost the ask
            // entirely -- both of which this asserts against.
            assertThat(book.bids()).containsExactly(new DepthLevel(100 * ONE, 3 * ONE));
            assertThat(book.asks()).containsExactly(new DepthLevel(101 * ONE, 4 * ONE));
        }
    }

    // --- helpers ---

    private String symbol(String prefix) {
        String symbol = prefix + (System.nanoTime() % 1_000_000);
        usedSymbols.add(symbol);
        return symbol;
    }

    private static boolean sequenceReached(DepthService depth, String symbol, long sequence) {
        return depth.of(Market.SPOT, symbol, 20)
                .filter(book -> book.lastSequence() >= sequence)
                .isPresent();
    }

    private static ConfigurableApplicationContext start() {
        return new SpringApplication(MarketDataApplication.class).run(
                "--spring.kafka.consumer.group-id=market-data-it-" + UUID.randomUUID(),
                "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                "--spring.kafka.consumer.auto-offset-reset=latest",
                "--spring.data.mongodb.uri=mongodb://localhost:27018/marketdata",
                "--server.port=0");
    }

    private static void publish(String symbol, List<Event> events) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            List<Future<?>> pending = new ArrayList<>();
            for (Event event : events) {
                byte[] payload = event.toBuilder().setSymbol(symbol).build().toByteArray();
                pending.add(producer.send(
                        new ProducerRecord<>(Topics.SPOT_EVENTS, symbol, payload)));
            }
            for (Future<?> sent : pending) {
                sent.get();
            }
        }
    }

    private static Event accepted(long sequence, Side side, long price, long restingQuantity) {
        return event(sequence).setOrderAccepted(OrderAccepted.newBuilder()
                .setOrderId(orderId())
                .setUserId(1L)
                .setSide(side)
                .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                .setTimeInForce(com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_GTC)
                .setPrice(price)
                .setQuantity(restingQuantity)
                .setRestingQuantity(restingQuantity)).build();
    }

    private static Event trade(long sequence, Side takerSide, long price, long quantity) {
        return event(sequence).setTradeExecuted(TradeExecuted.newBuilder()
                .setTradeId(orderId())
                .setTakerOrderId(orderId())
                .setMakerOrderId(orderId())
                .setTakerUserId(1L)
                .setMakerUserId(2L)
                .setTakerSide(takerSide)
                .setPrice(price)
                .setQuantity(quantity)
                .setQuoteQuantity(price / ONE * quantity)
                .setTakerFeeBps(10)
                .setMakerFeeBps(7)
                .setTakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                .setMakerStatus(OrderStatus.ORDER_STATUS_PARTIALLY_FILLED)).build();
    }

    private static Event cancel(long sequence, Side side, long price, long quantity) {
        return event(sequence).setOrderCanceled(OrderCanceled.newBuilder()
                .setOrderId(orderId())
                .setUserId(1L)
                .setReason(CancelReason.CANCEL_REASON_USER_REQUESTED)
                .setCanceledQuantity(quantity)
                .setPrice(price)
                .setSide(side)
                .setRested(true)).build();
    }

    private static Event.Builder event(long sequence) {
        return Event.newBuilder()
                .setSymbol("")   // replaced by publish(), so a batch can be reused
                .setSequence(sequence)
                .setTimestampUs(System.currentTimeMillis() * 1_000);
    }

    private static ByteString orderId() {
        return ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid()));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for the book to be built");
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable on " + port
                    + " -- start it before running this test (docker compose up -d)");
        }
    }
}
