package com.exchange.spot.matching.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * A real command through a real broker, matched by the real consumer, read back off the
 * real events topic.
 *
 * <p>Boots the application context so the listener is running in-process — otherwise the
 * test would silently depend on someone having started the engine by hand.
 *
 * <p>Skips rather than fails when Kafka is not reachable, so {@code gradlew build} works
 * on a machine with no Docker. A red build should mean broken code, not a missing broker.
 */
@SpringBootTest
// Spring caches contexts between test classes, so without this the engine started here
// would still be consuming when ReplayIT starts its own -- two engines, every command
// processed twice, and assertions that count events silently double.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EndToEndIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long ONE = 100_000_000L;
    private static final long PRICE = 6_325_000_000_000L;

    @Autowired
    private KafkaTemplate<String, byte[]> kafka;

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
    @DisplayName("a resting order then a crossing order produce an accept and a trade")
    void end_to_end() throws Exception {
        // A symbol nothing else has used, so the assertions cannot see another run's events.
        String symbol = "ITTEST" + System.nanoTime();

        kafka.send(Topics.SPOT_COMMANDS, symbol,
                place(symbol, 1, Side.SIDE_SELL, PRICE, ONE)).get();
        kafka.send(Topics.SPOT_COMMANDS, symbol,
                place(symbol, 2, Side.SIDE_BUY, PRICE, ONE)).get();

        List<Event> events = awaitEvents(symbol, 2, Duration.ofSeconds(30));

        assertEquals(2, events.size(), "expected an accept and a trade");

        Event accepted = events.get(0);
        assertEquals(Event.PayloadCase.ORDER_ACCEPTED, accepted.getPayloadCase());
        assertEquals(1, accepted.getSequence());
        assertEquals(ONE, accepted.getOrderAccepted().getRestingQuantity());

        Event trade = events.get(1);
        assertEquals(Event.PayloadCase.TRADE_EXECUTED, trade.getPayloadCase());
        assertEquals(2, trade.getSequence());
        assertEquals(PRICE, trade.getTradeExecuted().getPrice());
        assertEquals(ONE, trade.getTradeExecuted().getQuantity());
        assertEquals(7, trade.getTradeExecuted().getTakerFeeBps());     // stamped by the API
        assertEquals(-2, trade.getTradeExecuted().getMakerFeeBps());
        assertEquals(16, trade.getTradeExecuted().getTradeId().size()); // a 16-byte id
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
                // Its own group, so it reads everything and disturbs no other consumer.
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
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
