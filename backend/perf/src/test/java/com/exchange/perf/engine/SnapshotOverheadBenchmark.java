package com.exchange.perf.engine;

import static com.exchange.perf.engine.BenchmarkSupport.*;

import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * What snapshotting costs: the same 200,000-command workload against engines that differ only
 * in {@code snapshot-every}, against one that never snapshots. The gap is the overhead. The
 * interval trigger is disabled throughout so only the count is measured; the first run is a
 * discarded warm-up so no setting pays for a cold JVM.
 */
@Tag("performance")
class SnapshotOverheadBenchmark {

    private static final int N = 200_000;
    private static final String INTERVAL_OFF = "--engine.snapshot-max-interval-ms=100000000";

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("minio", 9000);
    }

    @Test
    void overhead_by_snapshot_every() throws Exception {
        run(1_000, 50_000); // warm-up, discarded
        double disabled = run(100_000_000, N);
        for (int every : new int[] {10_000, 30_000, 100_000}) {
            double tps = run(every, N);
            Report.value(Sections.ENGINE, Sections.SNAPSHOT_OVERHEAD + String.format("%,d", every),
                    (1 - tps / disabled) * 100, "% slower",
                    "200,000 resting orders, no fills, against the same run with snapshots off",
                    every == 30_000 ? "The default. Past it the gain is inside the run-to-run noise." : null);
        }
    }

    private double run(int every, int n) throws Exception {
        try (ConfigurableApplicationContext engine = startEngine("--engine.snapshot-every=" + every, INTERVAL_OFF);
             KafkaConsumer<String, byte[]> reader = primedLatestReader();
             KafkaProducer<String, byte[]> producer = producer()) {
            String s = symbol("PERFSNAP");
            double tps = commandsPerSecond(reader, restingLadder(producer, s, n));
            clear(producer, reader, s);
            return tps;
        }
    }
}
