package com.exchange.spot.matching.health;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.EngineHeartbeat;
import com.exchange.spot.matching.kafka.CommandConsumer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells the API which partitions have a live book, once a second, on
 * {@link Topics#SPOT_ENGINE_STATUS}. The API refuses a new order for a partition it has not
 * heard from in a few seconds ({@code EnginePresence} in libs/domain/spot); without this, an
 * order placed while the engine was down was accepted with a 202 and sat in Kafka, its funds
 * locked, until the engine came back -- minutes or hours later, at whatever the price was then.
 *
 * <p>A partition is announced only while all three hold: it is assigned here, its replay has
 * reached the committed offset ({@link CommandConsumer#livePartitions()}), and the consumer
 * thread is still polling ({@link ConsumerLivenessHealthIndicator#isPolling()}). Replaying
 * silently is not "live": the book is behind, and an order accepted then would be matched
 * against a stale one. A plain send, not the outbox: a lost heartbeat costs nothing, and a
 * heartbeat that survived its sender would be a lie.
 *
 * <p>Its own thread, which is the point -- the matching thread is the thing being watched. A
 * send that fails is logged once and tried again next tick; a broker that is down silences
 * every partition, which is the right answer, since no order could reach the engine either.
 */
@Component
public class EngineHeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(EngineHeartbeatPublisher.class);

    /** The API's silence threshold is a few of these (EnginePresence.SILENCE); the ratio is the
     *  slack for a slow broker or a GC pause before a market reads as paused. */
    static final long EVERY_MS = 1_000;

    private final CommandConsumer consumer;
    private final ConsumerLivenessHealthIndicator liveness;
    private final KafkaTemplate<String, byte[]> kafka;
    private final String instance;
    /** One warning per outage, not one per second per partition. */
    private final AtomicBoolean warned = new AtomicBoolean();

    @Autowired
    public EngineHeartbeatPublisher(CommandConsumer consumer, ConsumerLivenessHealthIndicator liveness,
                                    KafkaTemplate<String, byte[]> kafka) {
        this(consumer, liveness, kafka, hostName());
    }

    /** For tests: a fixed instance name. The annotation above is what tells Spring which of the two to use. */
    EngineHeartbeatPublisher(CommandConsumer consumer, ConsumerLivenessHealthIndicator liveness,
                             KafkaTemplate<String, byte[]> kafka, String instance) {
        this.consumer = consumer;
        this.liveness = liveness;
        this.kafka = kafka;
        this.instance = instance;
    }

    @Scheduled(fixedRate = EVERY_MS)
    public void beat() {
        if (!liveness.isPolling()) {
            return;
        }
        Set<Integer> live = consumer.livePartitions();
        long now = System.currentTimeMillis() * 1_000;
        for (int partition : live) {
            byte[] payload = EngineHeartbeat.newBuilder()
                    .setPartition(partition).setInstance(instance).setSentAtUs(now)
                    .build().toByteArray();
            // Partition N of the status topic, so a reader may follow one partition alone.
            kafka.send(Topics.SPOT_ENGINE_STATUS, partition, String.valueOf(partition), payload)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            warned.set(false);
                        } else if (warned.compareAndSet(false, true)) {
                            log.warn("heartbeat for partition {} did not reach the broker; the API "
                                    + "pauses its markets until one does", partition, failure);
                        }
                    });
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }
}
