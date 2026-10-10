package com.exchange.spot.matching.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.PlaceOrder;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import com.exchange.spot.matching.engine.MatchingEngine;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The recovery test. Starts an engine, feeds it orders, kills it, starts a second one, and
 * checks two things that pull in opposite directions:
 *
 * <ul>
 *   <li>the rebuilt book still holds the resting order — otherwise a restart silently
 *       deletes every open order on the venue</li>
 *   <li>no event was published twice — otherwise a restart invents trades</li>
 * </ul>
 *
 * <p>Requires {@code docker compose up -d}; skips otherwise.
 */
class ReplayIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long ONE = 100_000_000L;
    private static final long PRICE = 6_325_000_000_000L;

    @BeforeAll
    static void requireInfrastructure() {
        // Both: the engine now refuses to start when snapshot storage is unreachable, so a
        // run with only Kafka up would fail on a context that never started rather than
        // skip like a missing dependency should.
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
    @DisplayName("a restart rebuilds the book without re-publishing a single event")
    void restart_rebuilds_without_duplicating() throws Exception {
        String symbol = "REPLAY" + System.nanoTime();
        String group = "replay-it-" + UUID.randomUUID();

        // ---- first engine: one resting sell, then a buy that half-fills it
        try (ConfigurableApplicationContext first = startEngine(group)) {
            send(symbol, place(symbol, 1, Side.SIDE_SELL, PRICE, 2 * ONE));
            send(symbol, place(symbol, 2, Side.SIDE_BUY, PRICE, ONE));
            awaitEvents(symbol, 2, Duration.ofSeconds(30));
        }

        List<Event> afterFirstRun = awaitEvents(symbol, 2, Duration.ofSeconds(10));
        assertEquals(2, afterFirstRun.size(), "an accept and a trade");

        // ---- second engine, same group: must rebuild silently
        try (ConfigurableApplicationContext second = startEngine(group)) {
            // Give the replay time to run before asserting nothing new appeared.
            Thread.sleep(5_000);

            List<Event> afterRestart = awaitEvents(symbol, 3, Duration.ofSeconds(5));
            assertEquals(2, afterRestart.size(),
                    "the restart must not have re-published anything");

            // The rebuilt book must still hold the 1.0 remaining on that resting sell:
            // a buy for it should fill, which is only possible if the replay worked.
            send(symbol, place(symbol, 3, Side.SIDE_BUY, PRICE, ONE));
            List<Event> afterProof = awaitEvents(symbol, 3, Duration.ofSeconds(30));

            assertEquals(3, afterProof.size());
            Event trade = afterProof.get(2);
            assertEquals(Event.PayloadCase.TRADE_EXECUTED, trade.getPayloadCase(),
                    "the resting order survived the restart");
            assertEquals(PRICE, trade.getTradeExecuted().getPrice());
            // Sequences continued from the rebuilt state rather than restarting at 1.
            assertTrue(trade.getSequence() > afterFirstRun.get(1).getSequence(),
                    "sequence continued rather than resetting");
        }
    }

    private static ConfigurableApplicationContext startEngine(String group) {
        return new SpringApplication(com.exchange.spot.matching.MatchingEngineApplication.class)
                .run("--spring.main.web-application-type=none",
                     "--spring.kafka.consumer.group-id=" + group,
                     "--spring.kafka.bootstrap-servers=" + BOOTSTRAP,
                     "--logging.level.com.exchange=INFO");
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
                        .setUserId(userId)
                        .setSide(side)
                        .setOrderType(OrderType.ORDER_TYPE_LIMIT)
                        .setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                        .setPrice(price)
                        .setQuantity(qty)
                        .setMakerFeeBps(-2)
                        .setTakerFeeBps(7))
                .build()
                .toByteArray();
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
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
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
