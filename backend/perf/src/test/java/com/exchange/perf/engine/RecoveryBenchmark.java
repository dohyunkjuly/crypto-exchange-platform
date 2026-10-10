package com.exchange.perf.engine;

import static com.exchange.perf.engine.BenchmarkSupport.*;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.perf.report.Report;
import com.exchange.perf.report.Sections;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * How long trading stops after the engine dies. An engine takes 30,000 commands (the default
 * snapshot interval, so the whole tail replays from the log), is closed without ceremony, and
 * a second one with the same consumer group starts in its place. The clock runs from that
 * start to the first event the new engine publishes for a command sent the moment it began.
 */
@Tag("performance")
class RecoveryBenchmark {

    @BeforeAll
    static void requireInfrastructure() {
        requirePort("kafka", 9092);
        requirePort("minio", 9000);
    }

    @Test
    void restart_with_a_full_tail() throws Exception {
        String group = "--spring.kafka.consumer.group-id=perf-recovery-" + UUID.randomUUID();
        String symbol = symbol("PERFRECOVER");
        try (KafkaProducer<String, byte[]> producer = producer()) {
            try (ConfigurableApplicationContext first = startEngine(group);
                 KafkaConsumer<String, byte[]> reader = primedLatestReader()) {
                commandsPerSecond(reader, restingLadder(producer, symbol, 30_000));
            }
            // The engine is gone. A probe is already waiting in the log when the next one starts.
            ByteString probe = newCommandId();
            producer.send(new ProducerRecord<>(Topics.SPOT_COMMANDS, symbol, place(probe, symbol, Side.SIDE_BUY, PRICE0 - 100L, QTY)));
            producer.flush();
            try (KafkaConsumer<String, byte[]> reader = primedLatestReader()) {
                long t0 = System.currentTimeMillis();
                try (ConfigurableApplicationContext second = startEngine(group)) {
                    long t1 = awaitCommandId(reader, symbol, probe, Duration.ofSeconds(300));
                    Report.value(Sections.HEADLINE, Sections.RECOVERY_TIME, (t1 - t0) / 1000.0, "s",
                            "engine killed after 30,000 commands with no snapshot of them; time until it answers again",
                            "A restart replays at most this much; a snapshot every 30,000 commands keeps it bounded.");
                    clear(producer, reader, symbol);
                }
            }
        }
    }
}
