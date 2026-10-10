package com.exchange.spot.symbol;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.EngineHeartbeat;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Which command partitions a matching engine is running right now, as heard on
 * {@link Topics#SPOT_ENGINE_STATUS}. {@code OrderService} refuses a new order or an amend for
 * a partition that has gone quiet, so a trader learns at once that the market is paused
 * instead of getting a 202 for an order that sits in Kafka with its funds locked until an
 * engine comes back (docs/reference/trading-gate.md).
 *
 * <p>Judged by this process's clock at receipt, never by the heartbeat's own timestamp: the
 * question is "has anything arrived lately", and two machines' clocks have nothing to do with
 * it. A fresh process has heard nothing and pauses every market for its first few seconds; the
 * alternative, assuming live until told otherwise, is the gap this exists to close.
 *
 * <p>Fail closed, not open: a broker this process cannot reach silences every partition and
 * every market pauses. An order could not have reached the engine either.
 */
@Component
public class EnginePresence {

    private static final Logger log = LoggerFactory.getLogger(EnginePresence.class);

    /** Heartbeats come every second (the engine's EngineHeartbeatPublisher); five missed in a
     *  row is an engine that is gone, not a broker that is slow. */
    static final Duration SILENCE = Duration.ofSeconds(5);

    private final LongSupplier nanoTime;
    private final Map<Integer, Long> lastHeardNanos = new ConcurrentHashMap<>();

    public EnginePresence() {
        this(System::nanoTime);
    }

    EnginePresence(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    // The engine declares this topic with one partition per command partition. A consumer that
    // subscribes first must not auto-create it with the broker's default of one, or every
    // reader is stuck on partition 0 until its next metadata refresh.
    @KafkaListener(topics = Topics.SPOT_ENGINE_STATUS,
            groupId = "#{@enginePresenceConsumerGroup.id}",
            containerFactory = EnginePresenceConsumerConfig.FACTORY,
            properties = {"auto.offset.reset=latest", "allow.auto.create.topics=false"})
    public void onHeartbeat(ConsumerRecord<String, byte[]> record) {
        EngineHeartbeat beat;
        try {
            beat = EngineHeartbeat.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            log.warn("unreadable heartbeat on partition {}", record.partition(), e);
            return;
        }
        heard(beat.getPartition());
    }

    void heard(int partition) {
        lastHeardNanos.put(partition, nanoTime.getAsLong());
    }

    /** An engine has announced this partition within {@link #SILENCE}. */
    public boolean isLive(int partition) {
        Long at = lastHeardNanos.get(partition);
        return at != null && nanoTime.getAsLong() - at < SILENCE.toNanos();
    }
}
