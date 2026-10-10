package com.exchange.spot.matching.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.*;
import com.exchange.spot.matching.engine.MatchingEngine;
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
 * Proves the fix for a real double-execution bug: a market order whose PUBLISH failed
 * (not a crash -- the process stays up) must be matched exactly once, never twice, no
 * matter how many times {@code publishAll} has to retry before it lands.
 *
 * <p>Before this fix, a publish failure was allowed to escape {@code onCommand} as an
 * exception, which handed it to Spring's own listener-level redelivery. That redelivered
 * the SAME record to the SAME still-running process, which called {@code engine.handle()}
 * a second time against a book that was already mutated by the first attempt.
 * {@code OrderBook}'s duplicate-order-id guard only protects an order that is still
 * resting -- and a market order is never added to that index at all, so a second pass
 * matched it for real a second time.
 *
 * <p>Forces a genuine publish failure using real infrastructure, not a mock: this test
 * temporarily caps {@code max.message.bytes} on {@code events.v1} so low that no event can
 * be published, sends a market order into that window, confirms nothing lands while the
 * cap is in place, then restores it and confirms EXACTLY ONE trade appears -- not two.
 */
class PublishRetryDoesNotReprocessIT {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final long PRICE = 6_325_000_000_000L;
    private static final long RESTING_QTY = 1_000_000_000L;   // 10.0
    private static final long MARKET_QTY = 300_000_000L;      // 3.0
    // A market-firing BUY must carry quote_order_qty, never quantity (MatchingEngine
    // rejects the alternative) -- this is PRICE * 3.0 exactly, so it fills for exactly
    // MARKET_QTY of the resting sell, no dust.
    private static final long MARKET_BUY_QUOTE_QTY = 18_975_000_000_000L;

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
    @DisplayName("a market order whose publish must retry is matched exactly once, not twice")
    void publish_retry_does_not_double_match() throws Exception {
        String symbol = "PUBRETRY" + System.nanoTime();
        String group = "pubretry-it-" + UUID.randomUUID();

        try (ConfigurableApplicationContext engine = startEngine(group)) {
            // 1) A resting sell, published normally, before anything is broken.
            send(symbol, place(symbol, 1, Side.SIDE_SELL, PRICE, RESTING_QTY,
                    OrderType.ORDER_TYPE_LIMIT, TimeInForce.TIME_IN_FORCE_GTC));
            assertEquals(1, awaitEvents(symbol, 1, Duration.ofSeconds(30)).size());

            // 2) Cap events.v1 so low that no event can be published -- a real, broker-
            // enforced failure (RecordTooLargeException), not a simulated one.
            alterMaxMessageBytes("20");
            try {
                // 3) The market order that will need to retry its publish.
                send(symbol, marketBuyByQuote(symbol, 2, MARKET_BUY_QUOTE_QTY));

                // Give the retry loop several real, failing attempts (1s backoff each)
                // before lifting the cap, so this is testing sustained retry, not luck.
                // awaitEvents reads from "earliest", so it always re-finds the resting
                // sell's own event from step 1 -- the assertion is that the count has NOT
                // grown past that 1, not that it reads zero.
                Thread.sleep(4_000);
                assertEquals(1, awaitEvents(symbol, 2, Duration.ofSeconds(10)).size(),
                        "nothing new should have landed while every publish is failing");
            } finally {
                // 4) Let it through.
                removeMaxMessageBytesOverride();
            }

            // 5) Exactly one trade, for the correct quantity -- not two, not doubled.
            // Expecting 2 total: the resting sell's own OrderAccepted from step 1, plus
            // this trade. A fully-filled market taker gets no OrderAccepted of its own.
            List<Event> events = awaitEvents(symbol, 2, Duration.ofSeconds(30));
            long tradeCount = events.stream()
                    .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED)
                    .count();
            assertEquals(1, tradeCount, "the retried publish must not have re-matched the order");
            Event trade = events.stream()
                    .filter(e -> e.getPayloadCase() == Event.PayloadCase.TRADE_EXECUTED)
                    .findFirst().orElseThrow();
            assertEquals(MARKET_QTY, trade.getTradeExecuted().getQuantity(),
                    "filled quantity must reflect exactly one match, not a doubled one");

            // 6) The engine is not stuck: a normal command afterward still works.
            send(symbol, place(symbol, 3, Side.SIDE_SELL, PRICE + 100, RESTING_QTY,
                    OrderType.ORDER_TYPE_LIMIT, TimeInForce.TIME_IN_FORCE_GTC));
            assertEquals(3, awaitEvents(symbol, 3, Duration.ofSeconds(30)).size());
        }
    }

    private static void alterMaxMessageBytes(String bytes) throws Exception {
        runKafkaConfigs("--alter", "--add-config", "max.message.bytes=" + bytes);
    }

    private static void removeMaxMessageBytesOverride() throws Exception {
        runKafkaConfigs("--alter", "--delete-config", "max.message.bytes");
    }

    private static void runKafkaConfigs(String... alterArgs) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                "docker", "exec", "exchange-kafka",
                "/opt/kafka/bin/kafka-configs.sh",
                "--bootstrap-server", "localhost:9092",
                "--entity-type", "topics", "--entity-name", Topics.SPOT_EVENTS));
        command.addAll(List.of(alterArgs));
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("kafka-configs.sh exited " + exit);
        }
    }

    private static ConfigurableApplicationContext startEngine(String group) {
        return new SpringApplication(com.exchange.spot.matching.MatchingEngineApplication.class)
                .run("--spring.main.web-application-type=none",
                     "--spring.kafka.consumer.group-id=" + group,
                     "--spring.kafka.bootstrap-servers=" + BOOTSTRAP);
    }

    private static void send(String symbol, byte[] payload) throws Exception {
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))) {
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, payload)).get();
        }
    }

    private static byte[] place(String symbol, long userId, Side side, long price, long qty,
                                OrderType type, TimeInForce tif) {
        PlaceOrder.Builder order = PlaceOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setUserId(userId).setSide(side)
                .setOrderType(type).setTimeInForce(tif)
                .setQuantity(qty)
                .setMakerFeeBps(-2).setTakerFeeBps(7);
        if (price > 0) {
            order.setPrice(price);
        }
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(order)
                .build().toByteArray();
    }

    /** A market-firing BUY must carry quote_order_qty, never quantity -- see
     *  MatchingEngine's own enforcement of that rule. */
    private static byte[] marketBuyByQuote(String symbol, long userId, long quoteOrderQty) {
        PlaceOrder.Builder order = PlaceOrder.newBuilder()
                .setOrderId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setUserId(userId).setSide(Side.SIDE_BUY)
                .setOrderType(OrderType.ORDER_TYPE_MARKET)
                .setTimeInForce(TimeInForce.TIME_IN_FORCE_UNSPECIFIED)
                .setQuoteOrderQty(quoteOrderQty)
                .setMakerFeeBps(-2).setTakerFeeBps(7);
        return Command.newBuilder()
                .setCommandId(MatchingEngine.toBytes(UUID.randomUUID()))
                .setSymbol(symbol)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(order)
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
