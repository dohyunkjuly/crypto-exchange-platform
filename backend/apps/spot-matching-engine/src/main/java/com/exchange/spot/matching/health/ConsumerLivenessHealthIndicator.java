package com.exchange.spot.matching.health;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.NonResponsiveConsumerEvent;
import org.springframework.stereotype.Component;

/**
 * Turns Spring Kafka's non-responsive-consumer detection into something
 * {@code /actuator/health} can report and a liveness probe can act on.
 *
 * <p><b>The original of three.</b> {@code spot-settlement} and {@code market-data} carry
 * near-duplicates, deliberately not extracted: {@code libs/core} is HTTP machinery
 * (docs/STYLE.md) and {@code libs/common} is Spring-free, so neither fits, and a new
 * module for ~60 lines shared by three apps is not obviously right. A fourth Kafka app is the
 * moment to revisit that.
 *
 * <p>{@code CommandConsumer} already covers one kind of stuck -- a publish that retries itself
 * into {@code System.exit}. A wedged poll loop, a deadlock or a GC death spiral trips none of
 * that, and the process sits there looking alive while matching nothing for every symbol it
 * owns.
 *
 * <p>The container tracks the signal that distinguishes "stuck" from "quiet": whether the
 * listener thread still calls {@code poll()}, not whether {@code poll()} returns anything -- a
 * partition with no new orders is supposed to poll and get nothing. {@code no-poll-threshold}
 * and {@code monitor-interval} (application.yml) decide when that has happened.
 *
 * <p>There is no paired "recovered" event: it fires every monitor tick while the condition
 * holds and stops when polling resumes. So health is "was the last event recent", not a latched
 * flag, and it self-heals with no reset path.
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

    /** The same answer the probe gets, for {@link EngineHeartbeatPublisher}: a consumer that has
     *  stopped polling must also stop telling the API it is there. */
    public boolean isPolling() {
        Instant at = lastNonResponsiveAt.get();
        return at == null || Duration.between(at, Instant.now()).compareTo(RECOVERY_WINDOW) >= 0;
    }

    @Override
    public Health health() {
        if (isPolling()) {
            return Health.up().build();
        }
        Instant at = lastNonResponsiveAt.get();
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
