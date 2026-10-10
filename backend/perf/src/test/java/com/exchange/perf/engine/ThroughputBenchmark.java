package com.exchange.perf.engine;

import static com.exchange.perf.engine.BenchmarkSupport.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * How many commands one partition handles a second, end to end: Kafka in, a real engine, Kafka
 * out. The ceiling (nothing fills), a realistic mix (every sixth command fills five), and how
 * much the ceiling moves between runs, so the other numbers are read with that spread in mind.
 */
@Tag("performance")
class ThroughputBenchmark {

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("minio", 9000);
    }

    @Test
    void ceiling_crossing_and_spread() throws Exception {
        try (ConfigurableApplicationContext engine = startEngine();
             KafkaProducer<String, byte[]> producer = producer()) {

            // Warm-up: JIT and connections, discarded.
            try (KafkaConsumer<String, byte[]> reader = primedLatestReader()) {
                String warm = symbol("PERFWARM");
                commandsPerSecond(reader, restingLadder(producer, warm, 20_000));
                clear(producer, reader, warm);
            }

            List<Double> ceilings = new ArrayList<>();
            for (int run = 0; run < 3; run++) {
                try (KafkaConsumer<String, byte[]> reader = primedLatestReader()) {
                    String s = symbol("PERFBASE");
                    ceilings.add(commandsPerSecond(reader, restingLadder(producer, s, 100_000)));
                    clear(producer, reader, s);
                }
            }
            double ceiling = ceilings.stream().mapToDouble(d -> d).average().orElseThrow();
            double spread = (ceilings.stream().mapToDouble(d -> d).max().orElseThrow()
                    - ceilings.stream().mapToDouble(d -> d).min().orElseThrow()) / ceiling * 100;

            double crossing;
            try (KafkaConsumer<String, byte[]> reader = primedLatestReader()) {
                crossing = commandsPerSecond(reader, crossing(producer, symbol("PERFCROSS"), 10_000, 5));
            }

            Report.value(Sections.HEADLINE, Sections.ORDERS_PER_SECOND, ceiling, "commands/s",
                    "100,000 resting orders on one partition, three runs averaged",
                    "The capacity of one engine instance, which runs its partitions on one thread; the exchange scales by spreading partitions over more instances.");
            Report.value(Sections.ENGINE, Sections.CEILING, ceiling, "commands/s", "100,000 resting orders, nothing fills, three runs averaged", null);
            Report.value(Sections.ENGINE, Sections.CROSSING, crossing, "commands/s", "60,000 commands, every sixth fills five resting orders", null);
            Report.value(Sections.ENGINE, Sections.VARIANCE, spread, "%", "max minus min of the three ceiling runs, as a share of the average",
                    "Read every other number here with this much noise in mind.");
        }
    }
}
