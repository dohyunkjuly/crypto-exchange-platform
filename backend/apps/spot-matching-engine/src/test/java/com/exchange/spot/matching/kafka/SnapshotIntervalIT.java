package com.exchange.spot.matching.kafka;

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
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Proves the time-based half of the snapshot trigger fires on its own -- that a quiet
 * partition still gets snapshotted even when it will never reach snapshot-every.
 */
class SnapshotIntervalIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long PRICE = 6_325_000_000_000L;

    @BeforeAll
    static void requireInfrastructure() {
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
    @DisplayName("a quiet partition still snapshots on the time interval alone")
    void time_alone_triggers_a_snapshot() throws Exception {
        String symbol = "QUIET" + System.nanoTime();
        String group = "snapshot-interval-it-" + UUID.randomUUID();

        Map<TopicPartition, Long> before = pointerEndOffsets();

        // snapshot-every is set high enough that two commands can never reach it -- the
        // only way a snapshot can appear in this test is the interval, checked in
        // isolation from the count path this proves nothing about.
        try (ConfigurableApplicationContext engine =
                     startEngine(group, 1_000_000, 1_500)) {

            send(symbol, place(symbol, 1, Side.SIDE_SELL, PRICE, 100_000_000L));
            assertEqualsAtLeastOne(awaitEvents(symbol, 1, Duration.ofSeconds(30)));

            // Past the 1.5s interval. maybeSnapshot only runs when a command arrives, so a
            // second command is what gives it the chance to notice the interval elapsed.
            Thread.sleep(2_000);
            send(symbol, place(symbol, 2, Side.SIDE_SELL, PRICE, 100_000_000L));
            assertEqualsAtLeastOne(awaitEvents(symbol, 2, Duration.ofSeconds(30)));

            Thread.sleep(2_000);   // let the snapshot write land
        }

        Map<TopicPartition, Long> after = pointerEndOffsets();
        boolean newPointerWritten = after.entrySet().stream()
                .anyMatch(e -> e.getValue() > before.getOrDefault(e.getKey(), 0L));

        assertTrue(newPointerWritten,
                "a snapshot pointer should have appeared from the time trigger alone -- "
                        + "snapshot-every=1000000 made the count path unreachable with "
                        + "only two commands");
    }

    private static void assertEqualsAtLeastOne(List<Event> events) {
        assertTrue(!events.isEmpty(), "expected at least one event to be published");
    }

    private static Map<TopicPartition, Long> pointerEndOffsets() {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "interval-check-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class))) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                partitions.add(new TopicPartition(SnapshotStore.TOPIC, i));
            }
            consumer.assign(partitions);
            return consumer.endOffsets(partitions);
        }
    }

    private static ConfigurableApplicationContext startEngine(
            String group, int snapshotEvery, long snapshotMaxIntervalMs) {
        return new SpringApplication(com.exchange.spot.matching.MatchingEngineApplication.class)
                .run("--spring.main.web-application-type=none",
                     "--spring.kafka.consumer.group-id=" + group,
                     "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                     "--engine.snapshot-every=" + snapshotEvery,
                     "--engine.snapshot-max-interval-ms=" + snapshotMaxIntervalMs);
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
