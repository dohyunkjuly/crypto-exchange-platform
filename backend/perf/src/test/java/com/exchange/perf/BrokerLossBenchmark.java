package com.exchange.perf;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.*;
import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import com.google.protobuf.ByteString;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What three brokers buy. Commands flow at a steady pace to the running engine while one
 * broker is killed and, twenty seconds later, brought back. Two numbers: the longest gap
 * between consecutive events (the stall) and commands sent minus events seen (what was lost,
 * which with replication 3, min ISR 2 and acks=all must be zero). Runs only on the cluster
 * topology, against the compose stack's own engine.
 */
@Tag("performance")
class BrokerLossBenchmark {

    private static final String BOOTSTRAP = System.getenv().getOrDefault("PERF_KAFKA_BOOTSTRAP", "localhost:9092,localhost:9094,localhost:9095");
    private static final String VICTIM = System.getenv().getOrDefault("PERF_VICTIM_CONTAINER", "exchange-kafka-2");
    private static final int SECONDS = 60;
    private static final int PER_SECOND = 300;

    @BeforeAll
    static void requireCluster() {
        Assumptions.assumeTrue("cluster".equals(Report.topology()), "PERF_TOPOLOGY=cluster only");
        Stack.requirePort("kafka", 9092);
        Stack.requirePort("kafka-2", 9094);
    }

    @Test
    void one_broker_dies_mid_stream() throws Exception {
        String symbol = "PERFLOSS" + System.nanoTime();
        AtomicInteger seen = new AtomicInteger();
        AtomicLong longestGapMs = new AtomicLong();
        Thread reading = new Thread(() -> read(symbol, seen, longestGapMs));
        reading.setDaemon(true);
        reading.start();
        Thread.sleep(2_000);

        int sent = 0;
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"))) {
            long start = System.currentTimeMillis();
            for (int s = 0; s < SECONDS; s++) {
                if (s == 20) docker("kill", VICTIM);
                if (s == 40) docker("start", VICTIM);
                for (int i = 0; i < PER_SECOND; i++) {
                    producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(symbol, 6_325_000_000_000L + (long) sent * 100)));
                    sent++;
                }
                producer.flush();
                long next = start + (s + 1) * 1000L;
                Thread.sleep(Math.max(0, next - System.currentTimeMillis()));
            }
        }
        Thread.sleep(10_000);
        Report.value(Sections.CLUSTER, Sections.BROKER_LOSS_STALL, longestGapMs.get() / 1000.0, "s",
                "300 commands a second for 60 s; broker 2 killed at 20 s and restarted at 40 s; the longest silence on events.v1",
                "How long trading pauses when a broker dies: leader election plus the producers' retries.");
        Report.value(Sections.CLUSTER, Sections.BROKER_LOSS_LOST, sent - seen.get(), "commands",
                "commands sent minus events received, ten seconds after the run",
                "Must be zero: replication 3, min ISR 2 and acks=all mean a broker's death loses nothing.");
    }

    private static void read(String symbol, AtomicInteger seen, AtomicLong longestGapMs) {
        try (KafkaConsumer<String, byte[]> reader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP,
                ConsumerConfig.GROUP_ID_CONFIG, "perf-loss-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"))) {
            reader.subscribe(List.of(Topics.SPOT_EVENTS));
            long last = 0;
            while (!Thread.currentThread().isInterrupted()) {
                for (ConsumerRecord<String, byte[]> r : reader.poll(Duration.ofMillis(200))) {
                    try {
                        if (!Event.parseFrom(r.value()).getSymbol().equals(symbol)) continue;
                    } catch (Exception e) {
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    if (last > 0) longestGapMs.accumulateAndGet(now - last, Math::max);
                    last = now;
                    seen.incrementAndGet();
                }
            }
        }
    }

    private static byte[] place(String symbol, long price) {
        return Command.newBuilder()
                .setCommandId(uuid()).setSymbol(symbol).setTimestampUs(System.currentTimeMillis() * 1_000)
                .setPlaceOrder(PlaceOrder.newBuilder().setOrderId(uuid()).setUserId(1).setSide(Side.SIDE_BUY)
                        .setOrderType(OrderType.ORDER_TYPE_LIMIT).setTimeInForce(TimeInForce.TIME_IN_FORCE_GTC)
                        .setPrice(price).setQuantity(100_000_000L).setMakerFeeBps(-2).setTakerFeeBps(7))
                .build().toByteArray();
    }

    private static ByteString uuid() {
        UUID u = UUID.randomUUID();
        return ByteString.copyFrom(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
    }

    private static void docker(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).inheritIO().start();
        if (p.waitFor() != 0) throw new IllegalStateException("docker " + String.join(" ", args) + " failed");
    }
}
