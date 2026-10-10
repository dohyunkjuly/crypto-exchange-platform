package com.exchange.perf.engine;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.*;
import com.exchange.spot.matching.engine.MatchingEngine;
import com.google.protobuf.ByteString;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Assumptions;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Shared plumbing for the performance benchmarks in this package -- building commands,
 * producing them, reading back the resulting events, and starting a real engine instance
 * to measure -- over a REAL Kafka broker and a REAL Spring context. None of this calls
 * {@code MatchingEngine.handle} directly except the one benchmark that is deliberately
 * about in-memory cost alone; everything else would be measuring the wrong thing if it
 * bypassed Kafka.
 *
 * <p>Each benchmark ends in a {@code Report} line; docs/reference/performance.md says what the
 * numbers mean and docs/reference/performance.md why they come out as they do.
 */
final class BenchmarkSupport {

    static final String BOOTSTRAP = System.getenv().getOrDefault("PERF_KAFKA_BOOTSTRAP", "localhost:9092");

    /** A produced workload: the ids that bracket it, so its first and last event can be timed. */
    record Workload(String symbol, ByteString first, ByteString last, int commands) {
    }

    /** Every order rests: a ladder of rising bids with nothing to cross. One event a command. */
    static Workload restingLadder(KafkaProducer<String, byte[]> producer, String symbol, int n) {
        ByteString first = null, last = null;
        for (int i = 0; i < n; i++) {
            ByteString id = newCommandId();
            if (i == 0) first = id;
            if (i == n - 1) last = id;
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol,
                    place(id, symbol, Side.SIDE_BUY, PRICE0 + i * 100L, QTY)));
        }
        producer.flush();
        return new Workload(symbol, first, last, n);
    }

    /** Five asks rest, then one bid takes all five: six commands, ten events, repeated. */
    static Workload crossing(KafkaProducer<String, byte[]> producer, String symbol, int rounds, int fillsPerCross) {
        ByteString first = null, last = null;
        for (int r = 0; r < rounds; r++) {
            long price = PRICE0 + r * 100L;
            for (int i = 0; i < fillsPerCross; i++) {
                ByteString id = newCommandId();
                if (first == null) first = id;
                producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(id, symbol, Side.SIDE_SELL, price, QTY)));
            }
            ByteString cross = newCommandId();
            if (r == rounds - 1) last = cross;
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(cross, symbol, Side.SIDE_BUY, price, QTY * fillsPerCross)));
        }
        producer.flush();
        return new Workload(symbol, first, last, rounds * (fillsPerCross + 1));
    }

    /**
     * Commands a second from the first event to the last, over the real Kafka round trip. The
     * first event can be minutes away: a fresh engine restores the newest snapshot and replays
     * the log from there to its end before it answers, and that tail grows with every run on
     * this broker. The clock starts at the first event, so the wait costs patience, not accuracy.
     */
    static double commandsPerSecond(KafkaConsumer<String, byte[]> reader, Workload w) {
        long tFirst = awaitCommandId(reader, w.symbol(), w.first(), Duration.ofMinutes(15));
        long tLast = awaitCommandId(reader, w.symbol(), w.last(), Duration.ofSeconds(900));
        return (w.commands() - 1) / Math.max((tLast - tFirst) / 1000.0, 0.001);
    }

    static String symbol(String prefix) {
        return prefix + System.nanoTime();
    }

    /**
     * Leaves nothing behind: one cancel-all for the benchmark's own market, waited for. A
     * ladder that stayed would ride in every later snapshot, and every engine that restores
     * one -- the stack's included -- would carry and snapshot it forever.
     */
    static void clear(KafkaProducer<String, byte[]> producer, KafkaConsumer<String, byte[]> reader, String symbol) {
        ByteString id = newCommandId();
        byte[] cancelAll = com.exchange.perf.tools.ClearBenchmarkOrders.cancelAll(symbol);
        try {
            Command c = Command.parseFrom(cancelAll).toBuilder().setCommandId(id).build();
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, c.toByteArray()));
            producer.flush();
            awaitCommandId(reader, symbol, id, Duration.ofSeconds(600));
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
    }
    static final long PRICE0 = 6_325_000_000_000L;
    static final long QTY = 100_000_000L;

    private BenchmarkSupport() {
    }

    /** Skips with a clear reason rather than failing red when the fixture isn't running. */
    static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable on " + port
                    + " -- start it before running ./gradlew perfEngine (see docs/reference/performance.md)");
        }
    }

    /**
     * Starts a real engine in-process, same as the existing *IT tests do -- a full Spring
     * context, a real {@code CommandConsumer}, a real Kafka connection. A fresh random
     * consumer group means it always rebuilds from an empty book rather than whatever a
     * previous run left behind, and lets several benchmarks run back to back without
     * fighting over partition ownership.
     */
    static ConfigurableApplicationContext startEngine(String... extraArgs) {
        String[] args = new String[extraArgs.length + 3];
        args[0] = "--spring.main.web-application-type=none";
        args[1] = "--spring.kafka.consumer.group-id=perf-" + UUID.randomUUID();
        args[2] = "--spring.kafka.bootstrap-servers=" + BOOTSTRAP;
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        return new SpringApplication(com.exchange.spot.matching.MatchingEngineApplication.class)
                .run(args);
    }

    static KafkaProducer<String, byte[]> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.LINGER_MS_CONFIG, "20",
                ProducerConfig.BATCH_SIZE_CONFIG, "262144"));
    }

    static byte[] place(ByteString cmdId, String symbol, Side side, long price, long qty) {
        return Command.newBuilder()
                .setCommandId(cmdId)
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1).setSide(side)
                        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                        .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                        .setPrice(price).setQuantity(qty)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build().toByteArray();
    }

    static byte[] stopLossBuy(ByteString cmdId, String symbol, long stopPrice, long qty) {
        return Command.newBuilder()
                .setCommandId(cmdId)
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(1).setSide(Side.SIDE_BUY)
                        .setOrderType(OrderType.ORDER_TYPE_STOP_LOSS)
                        .setTimeInForce(TimeInForce.TIME_IN_FORCE_IOC)
                        .setStopPrice(stopPrice).setQuantity(qty)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build().toByteArray();
    }

    static ByteString newCommandId() {
        return MatchingEngine.toBytes(UUID.randomUUID());
    }

    /**
     * A reader subscribed and confirmed-assigned at "latest" -- i.e. primed to see only
     * events published from this point forward. Not "earliest": events.v1 accumulates
     * across every benchmark run, and scanning past a growing multi-hundred-thousand-record
     * backlog would itself become the thing being measured.
     *
     * <p>Must be created and fully assigned BEFORE anything is produced. subscribe() does
     * not block for the join/sync round trip, so producing immediately after it risks
     * "latest" resolving to an offset after records that are about to land, missing them.
     */
    static KafkaConsumer<String, byte[]> primedLatestReader() {
        KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "perf-bench-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"));
        reader.subscribe(List.of(Topics.SPOT_EVENTS));
        for (int tries = 0; tries < 25 && reader.assignment().isEmpty(); tries++) {
            reader.poll(Duration.ofMillis(200));
        }
        if (reader.assignment().isEmpty()) {
            reader.close();
            throw new IllegalStateException("reader never got a partition assignment");
        }
        return reader;
    }

    /** Wall-clock time (ms) at which an event carrying this command_id was observed. */
    static long awaitCommandId(KafkaConsumer<String, byte[]> reader, String symbol,
                               ByteString commandId, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, byte[]> record : reader.poll(Duration.ofMillis(200))) {
                Event event = parseOrNull(record.value());
                if (event != null && event.getSymbol().equals(symbol)
                        && event.getCommandId().equals(commandId)) {
                    return System.currentTimeMillis();
                }
            }
        }
        throw new IllegalStateException("timed out waiting for command_id on " + symbol);
    }

    /** Last wall-clock time any event for this symbol arrived, after {@code quietMs} of
     *  silence -- used when a command fires an unknown number of downstream events (a
     *  cascade) rather than one predictable one. */
    static long awaitQuiet(KafkaConsumer<String, byte[]> reader, String symbol,
                           long quietMs, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long lastSeen = 0;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = reader.poll(Duration.ofMillis(300));
            boolean gotAny = false;
            for (ConsumerRecord<String, byte[]> record : records) {
                Event event = parseOrNull(record.value());
                if (event != null && event.getSymbol().equals(symbol)) {
                    lastSeen = System.currentTimeMillis();
                    gotAny = true;
                }
            }
            if (!gotAny && lastSeen > 0 && System.currentTimeMillis() - lastSeen > quietMs) {
                return lastSeen;
            }
        }
        if (lastSeen > 0) return lastSeen;
        throw new IllegalStateException("timed out waiting for events on " + symbol);
    }

    private static Event parseOrNull(byte[] bytes) {
        try {
            return Event.parseFrom(bytes);
        } catch (Exception e) {
            return null;
        }
    }
}
