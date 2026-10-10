package com.exchange.marketdata.testsupport;

import com.exchange.common.kafka.Topics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Test-side setup for {@code exchange.spot.events.v1}.
 *
 * <p>Exists because {@code market-data} consumes that topic but does not declare it --
 * {@code spot-matching-engine} owns the {@code NewTopic} beans. On a developer's machine
 * the topic is usually already there from an earlier engine run, so tests here passed by
 * accident. On a fresh broker (CI) it is not, and Kafka auto-creates it on subscribe with
 * the broker default of <b>one</b> partition, so any test waiting for a specific
 * partition count waits forever.
 *
 * <p>So the topic is created explicitly, with the partition count the app's
 * {@code listener.concurrency} is sized against. Creation is idempotent: an existing topic
 * is left exactly as it is.
 */
public final class EventsTopic {

    /** Matches spot-matching-engine's EventTopicConfig and market-data's
     *  listener.concurrency -- one partition per listener thread. */
    public static final int PARTITIONS = 8;

    private EventsTopic() {
    }

    /** Creates the topic if it is missing. Safe to call from every test. */
    public static void ensureExists(String bootstrapServers) {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(Topics.SPOT_EVENTS, PARTITIONS, (short) 1)))
                    .all().get();
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException("could not create " + Topics.SPOT_EVENTS, e);
            }
            // Already there, with whatever partition count it was created with. Fine.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted creating " + Topics.SPOT_EVENTS, e);
        }
    }

    /**
     * Blocks until the app's consumer group owns every partition of the topic.
     *
     * <p>A fixed sleep is not enough once {@code listener.concurrency} is above one: each
     * listener thread joins the group separately and every join rebalances, so eight take
     * far longer to settle than one. With {@code auto-offset-reset=latest} and no committed
     * offsets, a partition handed to its owner AFTER publishing resolves past the test's
     * events and skips them -- which surfaces as a timeout rather than an error, because
     * nothing failed; the events were simply never read.
     */
    public static void awaitAssignment(ConfigurableApplicationContext ctx, Duration timeout)
            throws InterruptedException {
        KafkaListenerEndpointRegistry registry =
                ctx.getBean(KafkaListenerEndpointRegistry.class);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        long assigned = 0;
        while (System.currentTimeMillis() < deadline) {
            assigned = registry.getListenerContainers().stream()
                    .map(container -> container.getAssignedPartitions())
                    .filter(Objects::nonNull)
                    .mapToLong(java.util.Collection::size)
                    .sum();
            if (assigned == PARTITIONS) {
                // Assignment fires before the consumer's first fetch; a beat here avoids
                // racing it.
                Thread.sleep(500);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("consumer never took all " + PARTITIONS
                + " partitions of " + Topics.SPOT_EVENTS + " (got " + assigned + ")");
    }
}
