package com.exchange.marketdata.health;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.stereotype.Component;

/**
 * Kafka-consumer liveness for {@code /actuator/health}. The third near-duplicate of
 * {@code spot-matching-engine}'s class of the same name, which carries the reasoning for both
 * the duplication and the poll-based signal.
 *
 * <p>MongoDB is covered for free: Actuator auto-configures a {@code MongoHealthIndicator} once
 * a Mongo client bean exists, so this endpoint already reflects a database outage.
 */
@Component
public class ConsumerLivenessHealthIndicator implements HealthIndicator {

    /** Longer than one monitor-interval so a single tick does not flap health; short
     *  enough that a genuinely stuck consumer reports unhealthy well within one
     *  liveness-probe interval. */
    private static final Duration RECOVERY_WINDOW = Duration.ofSeconds(30);

    private final AtomicReference<NonResponsiveConsumerEvent> lastNonResponsive =
            new AtomicReference<>();
    private final AtomicReference<Instant> lastNonResponsiveAt = new AtomicReference<>();

    @EventListener
    public void onNonResponsive(NonResponsiveConsumerEvent event) {
        lastNonResponsive.set(event);
        lastNonResponsiveAt.set(Instant.now());
    }

    @Override
    public Health health() {
        Instant at = lastNonResponsiveAt.get();
        if (at == null || Duration.between(at, Instant.now()).compareTo(RECOVERY_WINDOW) >= 0) {
            return Health.up().build();
        }
        NonResponsiveConsumerEvent event = lastNonResponsive.get();
        return Health.down()
                .withDetail("reason", "consumer thread stopped polling")
                .withDetail("listenerId", event.getListenerId())
                .withDetail("topicPartitions", event.getTopicPartitions())
                .withDetail("timeSinceLastPollMs", event.getTimeSinceLastPoll())
                .withDetail("detectedAt", at)
                .build();
    }
}
