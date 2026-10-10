package com.exchange.perf.engine;

import static com.exchange.perf.engine.BenchmarkSupport.*;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Do stop orders firing together stall everyone else? Throughput is measured in windows, not
 * one average, because an average hides a stall: twenty windows of 2,000 plain orders, and
 * every fifth window instead parks 500 stops and fires them with one trade. Two numbers: the
 * slowest plain window that follows a cascade against the median (a lingering effect would
 * show there), and the pause for 5,000 stops in one command, the size at which it is felt.
 */
@Tag("performance")
class ConditionalOrderThroughputBenchmark {

    private static final int WINDOWS = 20;
    private static final int PLAIN_PER_WINDOW = 2_000;
    private static final int STOPS_PER_CASCADE = 500;

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("minio", 9000);
    }

    @Test
    void windows_with_cascades_every_fifth() throws Exception {
        String symbol = symbol("PERFCOND");
        try (ConfigurableApplicationContext engine = startEngine();
             KafkaConsumer<String, byte[]> reader = primedLatestReader();
             KafkaProducer<String, byte[]> producer = producer()) {

            long price = PRICE0;
            // A first trade, so the stops have a last price to fire from.
            price = prime(producer, reader, symbol, price);

            List<Double> plainTps = new ArrayList<>();
            for (int w = 0; w < WINDOWS; w++) {
                boolean cascade = w > 0 && w % 5 == 0;
                long tStart = System.currentTimeMillis();
                if (cascade) {
                    price = parkAndFire(producer, symbol, price, STOPS_PER_CASCADE);
                    awaitQuiet(reader, symbol, 1000, Duration.ofSeconds(60));
                } else {
                    ByteString last = null;
                    for (int i = 0; i < PLAIN_PER_WINDOW; i++) {
                        price += 100L;
                        last = newCommandId();
                        producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(last, symbol, Side.SIDE_BUY, price, QTY)));
                    }
                    producer.flush();
                    long tEnd = awaitCommandId(reader, symbol, last, Duration.ofSeconds(60));
                    if (w >= 2) plainTps.add(PLAIN_PER_WINDOW / Math.max((tEnd - tStart) / 1000.0, 0.001)); // first two warm up
                }
            }
            double[] sorted = plainTps.stream().mapToDouble(d -> d).sorted().toArray();
            double median = sorted[sorted.length / 2];
            Report.value(Sections.ENGINE, Sections.CASCADE_WINDOWS, sorted[0] / median * 100, "% of median",
                    "the slowest of 15 windows of 2,000 ordinary orders against the median window; every fifth window fired 500 stops with one trade",
                    "Near 100 means stops firing cost the rest of the market nothing visible; short windows also catch GC and JIT pauses, so read it with the spread above.");

            // One large cascade: from the firing trade to the last event it causes, publishing included.
            long t0 = System.currentTimeMillis();
            parkAndFire(producer, symbol, price, 5_000);
            long t1 = awaitQuiet(reader, symbol, 1500, Duration.ofSeconds(120));
            Report.value(Sections.ENGINE, Sections.CASCADE_COST, t1 - t0, "ms",
                    "5,000 parked stops fired by one trade: from the trade to the cascade's last event; the partition does nothing else meanwhile",
                    "The one place a burst is felt: a market with thousands of stops at one price pauses for about this long.");
            clear(producer, reader, symbol);
        }
    }

    private static long prime(KafkaProducer<String, byte[]> producer, KafkaConsumer<String, byte[]> reader, String symbol, long price) {
        producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(newCommandId(), symbol, Side.SIDE_SELL, price, QTY)));
        ByteString id = newCommandId();
        producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(id, symbol, Side.SIDE_BUY, price, QTY)));
        producer.flush();
        awaitCommandId(reader, symbol, id, Duration.ofMinutes(15)); // the first event may wait on a replay
        return price;
    }

    /** Parks {@code stops} stop-loss buys above the price, then trades through them all. */
    private static long parkAndFire(KafkaProducer<String, byte[]> producer, String symbol, long price, int stops) {
        for (int i = 1; i <= stops; i++) {
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, stopLossBuy(newCommandId(), symbol, price + i * 100L, QTY)));
        }
        long high = price + (stops + 10L) * 100L;
        producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(newCommandId(), symbol, Side.SIDE_SELL, high, QTY * (stops + 1))));
        producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(newCommandId(), symbol, Side.SIDE_BUY, high, QTY * (stops + 1))));
        producer.flush();
        return high;
    }
}
