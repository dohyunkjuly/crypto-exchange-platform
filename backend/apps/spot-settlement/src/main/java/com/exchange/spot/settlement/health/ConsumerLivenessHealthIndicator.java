package com.exchange.spot.settlement.health;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.stereotype.Component;

/**
 * Kafka-consumer liveness for {@code /actuator/health}. A near-duplicate of
 * {@code spot-matching-engine}'s class of the same name, which carries the reasoning for both
 * the duplication and the poll-based signal.
 *
 * <p>Unlike the engine, this app has a database, and Actuator auto-configures a
 * {@code DataSourceHealthIndicator} once a {@code DataSource} bean exists -- so a MySQL outage
 * is already reflected here and this class only ever needed to cover Kafka.
 */
@Component
public class ConsumerLivenessHealthIndicator implements HealthIndicator {

    /** Longer than one monitor-interval, so a single tick doesn't flap health between
     *  checks; short enough that a genuinely stuck consumer is reported unhealthy well
     *  within one liveness-probe interval. */
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
