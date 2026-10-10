package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.common.kafka.Topics;
import com.exchange.common.id.Ulid;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderStatus;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import com.exchange.marketdata.MarketDataApplication;
import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleRepository;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.tape.PublicTrade;
import com.exchange.marketdata.tape.TradeTapeService;
import com.exchange.marketdata.testsupport.EventsTopic;
import com.exchange.marketdata.testsupport.SeededMarketData;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/**
 * The end-to-end proof, against a REAL Kafka broker and a REAL MongoDB: trades published
 * to {@code exchange.spot.events.v1} become candles.
 *
 * <p>The second test is the one that matters most. Kafka delivers at least once, and
 * candle volume is accumulated -- so a redelivered batch is the failure mode that would
 * corrupt this collection silently, with no exception and no log line, and only show up
 * as a chart that disagrees with the trade tape. Publishing the identical events twice
 * and asserting the volume did not move is the only way to know the watermark actually
 * works end to end rather than merely in a mocked unit test.
 *
 * <p>Skipped, not failed, when the infrastructure is not running -- same convention as
 * {@code WhaleSettlementIT}.
 */
class CandleBuildingIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long PRICE = 100_00000000L;      // 100.00000000
    private static final long QUANTITY = 2_00000000L;     // 2.0
    private static final long QUOTE_QUANTITY = 200_00000000L;

    /** Every symbol this test invented, so its candles can be removed afterwards. Held
     *  rather than derived, because the suffix is random per test. */
    private final List<String> usedSymbols = new ArrayList<>();

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("mongodb", 27018);
        // market-data consumes this topic but does not declare it -- see EventsTopic.
        EventsTopic.ensureExists(BOOTSTRAP);
    }

    /** These candles are proof of a past run, not data anyone wants: leaving them behind is
     *  what turned this collection into several hundred stray documents. */
    @AfterEach
    void removeTestCandles() {
        if (usedSymbols.isEmpty()) {
            return;
        }
        try (ConfigurableApplicationContext ctx = start()) {
            SeededMarketData.purge(ctx, usedSymbols);
        }
    }

    private String symbol(String prefix) {
        String symbol = prefix + (System.nanoTime() % 1_000_000);
        usedSymbols.add(symbol);
        return symbol;
    }

    @Test
    void buildsACandleForEveryIntervalFromPublishedTrades() throws Exception {
        String symbol = symbol("ITCANDLE");
        Instant at = Instant.parse("2026-09-05T14:37:10Z");

        try (ConfigurableApplicationContext ctx = start()) {
            CandleRepository candles = ctx.getBean(CandleRepository.class);
            EventsTopic.awaitAssignment(ctx, java.time.Duration.ofMinutes(1));

            publish(symbol, List.of(
                    trade(1, at, PRICE, QUANTITY, QUOTE_QUANTITY),
                    trade(2, at.plusSeconds(5), 130_00000000L, 1_00000000L, 130_00000000L),
                    trade(3, at.plusSeconds(9), 90_00000000L, 3_00000000L, 270_00000000L)));

            // Until the last trade is in, not the first: the three may arrive in more than one
            // batch, and a candle holding only the first would pass "present" and fail the rest.
            await(() -> oneMinute(candles, symbol, at).filter(c -> c.getLastSequence() == 3L).isPresent());

            // Every configured interval got its own bucket, not just the base one.
            for (CandleInterval interval : CandleInterval.values()) {
                Optional<Candle> candle = candles.findById(Market.SPOT,
                        Candle.idOf(Market.SPOT, symbol, interval, interval.bucketStart(at)));
                assertThat(candle).as("candle for %s", interval.code()).isPresent();
            }

            Candle minute = oneMinute(candles, symbol, at).orElseThrow();
            assertThat(minute.getOpen()).isEqualTo(PRICE);              // first trade
            assertThat(minute.getHigh()).isEqualTo(130_00000000L);
            assertThat(minute.getLow()).isEqualTo(90_00000000L);
            assertThat(minute.getClose()).isEqualTo(90_00000000L);      // last trade
            assertThat(minute.getVolume()).isEqualTo(6_00000000L);      // 2 + 1 + 3
            assertThat(minute.getQuoteVolume()).isEqualTo(600_00000000L);
            assertThat(minute.getTradeCount()).isEqualTo(3);
            assertThat(minute.getLastSequence()).isEqualTo(3L);
        }
    }

    /**
     * The tape is built from the same batch, so it is proved on the same events.
     *
     * <p>The assertion that matters is what the stored document does <b>not</b> contain.
     * Every field on the wire is available to the writer, including the two user ids and
     * both fee rates, and a public tape that carried any of them would let anyone watching
     * a screen reconstruct who is trading. Nothing would fail if it did.
     */
    @Test
    void printsTradesToThePublicTapeWithNoCounterpartyOrFees() throws Exception {
        String symbol = symbol("ITTAPE");
        Instant at = Instant.parse("2026-09-05T11:02:00Z");

        try (ConfigurableApplicationContext ctx = start()) {
            TradeTapeService tape = ctx.getBean(TradeTapeService.class);
            EventsTopic.awaitAssignment(ctx, java.time.Duration.ofMinutes(1));

            publish(symbol, List.of(
                    trade(1, at, PRICE, QUANTITY, QUOTE_QUANTITY),
                    trade(2, at.plusSeconds(1), 130_00000000L, 1_00000000L, 130_00000000L)));

            await(() -> tape.recent(Market.SPOT, symbol, 10).size() == 2);

            // Newest first, which is the order a tape prints.
            assertThat(tape.recent(Market.SPOT, symbol, 10))
                    .extracting(PublicTrade::sequence).containsExactly(2L, 1L);
            assertThat(tape.recent(Market.SPOT, symbol, 1))
                    .singleElement()
                    .returns(130_00000000L, PublicTrade::price)
                    .returns("BUY", PublicTrade::takerSide);

            String stored = ctx.getBean(org.springframework.data.mongodb.core.MongoTemplate.class)
                    .getCollection(Market.SPOT.tapeCollection())
                    .find(new org.bson.Document("_id", symbolTapeId(symbol)))
                    .first().toJson();
            assertThat(stored)
                    .doesNotContain("UserId").doesNotContain("userId")
                    .doesNotContain("FeeBps").doesNotContain("feeBps");
        }
    }

    private static String symbolTapeId(String symbol) {
        return com.exchange.marketdata.tape.TradeTape.idOf(Market.SPOT, symbol);
    }

    @Test
    void republishingTheSameTradesDoesNotDoubleCountVolume() throws Exception {
        String symbol = symbol("ITREPLAY");
        Instant at = Instant.parse("2026-09-05T09:12:30Z");
        List<Event> batch = List.of(
                trade(1, at, PRICE, QUANTITY, QUOTE_QUANTITY),
                trade(2, at.plusSeconds(3), PRICE, QUANTITY, QUOTE_QUANTITY));

        try (ConfigurableApplicationContext ctx = start()) {
            CandleRepository candles = ctx.getBean(CandleRepository.class);
            EventsTopic.awaitAssignment(ctx, java.time.Duration.ofMinutes(1));

            publish(symbol, batch);
            await(() -> oneMinute(candles, symbol, at)
                    .filter(c -> c.getTradeCount() == 2).isPresent());

            long volumeAfterFirst = oneMinute(candles, symbol, at).orElseThrow().getVolume();
            assertThat(volumeAfterFirst).isEqualTo(4_00000000L);

            // Exactly what a Kafka redelivery looks like: the same events, again.
            publish(symbol, batch);
            Thread.sleep(3_000); // give it time to genuinely process (and wrongly apply)

            Candle after = oneMinute(candles, symbol, at).orElseThrow();
            assertThat(after.getVolume()).isEqualTo(volumeAfterFirst);
            assertThat(after.getTradeCount()).isEqualTo(2);
            assertThat(after.getLastSequence()).isEqualTo(2L);
        }
    }

    private static Optional<Candle> oneMinute(CandleRepository candles, String symbol, Instant at) {
        return candles.findById(Market.SPOT, Candle.idOf(Market.SPOT, symbol,
                CandleInterval.ONE_MINUTE, CandleInterval.ONE_MINUTE.bucketStart(at)));
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
            List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
            for (Event event : events) {
                // The symbol is stamped here rather than in trade(), so a batch can be
                // republished verbatim to a different symbol per test.
                byte[] payload = event.toBuilder().setSymbol(symbol).build().toByteArray();
                pending.add(producer.send(new ProducerRecord<>(
                        Topics.SPOT_EVENTS, symbol, payload)));
            }
            for (java.util.concurrent.Future<?> f : pending) {
                f.get();
            }
        }
    }

    private static Event trade(long sequence, Instant at, long price, long qty, long quoteQty) {
        TradeExecuted trade = TradeExecuted.newBuilder()
                .setTradeId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setTakerOrderId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setMakerOrderId(ByteString.copyFrom(Ulid.toBytes(Ulid.randomUuid())))
                .setTakerUserId(1L)
                .setMakerUserId(2L)
                .setTakerSide(Side.SIDE_BUY)
                .setPrice(price)
                .setQuantity(qty)
                .setQuoteQuantity(quoteQty)
                .setTakerFeeBps(10)
                .setMakerFeeBps(7)
                .setTakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                .setMakerStatus(OrderStatus.ORDER_STATUS_FILLED)
                .build();
        return Event.newBuilder()
                .setSymbol("")   // replaced below by the caller's symbol
                .setSequence(sequence)
                .setTimestampUs(at.toEpochMilli() * 1_000)
                .setTradeExecuted(trade)
                .build();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("timed out waiting for candles to be built");
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
