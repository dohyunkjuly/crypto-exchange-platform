package com.exchange.spot.matching.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.*;
import com.exchange.spot.matching.engine.MatchingEngine;
import com.exchange.spot.matching.snapshot.SnapshotStore;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves recovery uses the snapshot rather than replaying from the beginning, and that a
 * restored book still matches correctly.
 */
class SnapshotRecoveryIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long ONE = 100_000_000L;
    private static final long PRICE = 6_325_000_000_000L;

    @BeforeAll
    static void requireInfrastructure() {
        // Both, now: the pointer goes on Kafka and the snapshot itself goes to object
        // storage, so a run with only one of them up would fail on a missing snapshot and
        // look like a recovery bug.
        requirePort("kafka", 9092);
        requirePort("minio", 9000);
        EngineTestGuards.abortIfEngineAppIsRunning();
    }

    private static void requirePort(String service, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 500);
        } catch (Exception e) {
            Assumptions.abort(service + " not reachable -- run `docker compose up -d`");
        }
    }

    @Test
    @DisplayName("a restart restores from a snapshot and the rebuilt book still fills")
    void recovery_uses_the_snapshot() throws Exception {
        String symbol = "SNAP" + System.nanoTime();
        String group = "snapshot-it-" + UUID.randomUUID();

        // snapshot-every=1 so a snapshot exists after the very first command.
        try (ConfigurableApplicationContext first = startEngine(group, 1)) {
            send(symbol, place(symbol, 1, Side.SIDE_SELL, PRICE, 3 * ONE));
            send(symbol, place(symbol, 2, Side.SIDE_BUY, PRICE, ONE));
            assertEquals(2, awaitEvents(symbol, 2, Duration.ofSeconds(30)).size());
            Thread.sleep(2_000);   // let the snapshot land
        }

        // A snapshot for the partition this symbol hashes to must now exist.
        assertTrue(anySnapshotExists(), "the engine should have written a snapshot");

        try (ConfigurableApplicationContext second = startEngine(group, 1)) {
            Thread.sleep(5_000);

            assertEquals(2, awaitEvents(symbol, 3, Duration.ofSeconds(5)).size(),
                    "recovery must not re-publish");

            // 2.0 remains on that resting sell. If the snapshot restored it, this fills.
            send(symbol, place(symbol, 3, Side.SIDE_BUY, PRICE, 2 * ONE));
            List<Event> events = awaitEvents(symbol, 3, Duration.ofSeconds(30));

            assertEquals(3, events.size());
            Event trade = events.get(2);
            assertEquals(Event.PayloadCase.TRADE_EXECUTED, trade.getPayloadCase());
            assertEquals(2 * ONE, trade.getTradeExecuted().getQuantity(),
                    "the partially filled remainder survived the snapshot");
        }
    }

    /** The producer here is deliberately not idempotent, so the same bytes twice really are
     *  two records in the log -- the case a relay re-sending after a crash would produce. */
    @Test
    @DisplayName("a command re-sent after a restart is skipped: the snapshot carries what was already done")
    void a_repeat_after_a_restart_is_skipped() throws Exception {
        String symbol = "DUPE" + System.nanoTime();
        String group = "dedupe-it-" + UUID.randomUUID();
        byte[] takes = place(symbol, 2, Side.SIDE_BUY, PRICE, ONE);

        try (ConfigurableApplicationContext first = startEngine(group, 1)) {
            send(symbol, place(symbol, 1, Side.SIDE_SELL, PRICE, 3 * ONE));
            send(symbol, takes);
            assertEquals(2, awaitEvents(symbol, 2, Duration.ofSeconds(30)).size());
            Thread.sleep(2_000);   // let the snapshot land
        }

        try (ConfigurableApplicationContext second = startEngine(group, 1)) {
            Thread.sleep(5_000);
            send(symbol, takes);   // the same command again, byte for byte
            // A fresh order afterwards proves the engine is alive and processing.
            send(symbol, place(symbol, 3, Side.SIDE_BUY, PRICE, ONE));

            List<Event> events = awaitEvents(symbol, 4, Duration.ofSeconds(10));
            assertEquals(3, events.size(), "the repeat must add no events");
            Event trade = events.get(2);
            assertEquals(Event.PayloadCase.TRADE_EXECUTED, trade.getPayloadCase());
            assertEquals(3, trade.getTradeExecuted().getTakerUserId(),
                    "the one new trade is the fresh order's, not the repeat's");
        }
    }

    private static boolean anySnapshotExists() {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "snapcheck-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            List<org.apache.kafka.common.TopicPartition> partitions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                partitions.add(new org.apache.kafka.common.TopicPartition(
                        SnapshotStore.TOPIC, i));
            }
            consumer.assign(partitions);
            Map<org.apache.kafka.common.TopicPartition, Long> ends =
                    consumer.endOffsets(partitions);
            return ends.values().stream().anyMatch(offset -> offset > 0);
        }
    }

    private static ConfigurableApplicationContext startEngine(String group, int snapshotEvery) {
        return new SpringApplication(com.exchange.spot.matching.MatchingEngineApplication.class)
                .run("--spring.main.web-application-type=none",
                     "--spring.kafka.consumer.group-id=" + group,
                     "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                     "--engine.snapshot-every=" + snapshotEvery);
    }

    private static void send(String symbol, byte[] payload) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, payload)).get();
        }
    }

    private static byte[] place(String symbol, long userId, Side side, long price, long qty) {
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder()
                        .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                        .setUserId(userId).setSide(side)
                        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                        .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                        .setPrice(price).setQuantity(qty)
                        .setMakerFeeBps(-2).setTakerFeeBps(7))
                .build().toByteArray();
    }

    private static List<Event> awaitEvents(String symbol, int expected, Duration timeout)
            throws Exception {
        List<Event> found = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(List.of(Topics.SPOT_EVENTS));
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            while (System.currentTimeMillis() < deadline && found.size() < expected) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    Event event = Event.parseFrom(record.value());
                    if (event.getSymbol().equals(symbol)) {
                        found.add(event);
                    }
                }
            }
        }
        return found;
    }
}
