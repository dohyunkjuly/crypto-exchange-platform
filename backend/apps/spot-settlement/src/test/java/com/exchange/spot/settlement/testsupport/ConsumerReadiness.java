package com.exchange.spot.settlement.testsupport;

import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.OrderAccepted;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.Assumptions;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Waits until the settlement consumer actually owns partitions before a test publishes.
 *
 * <p>These tests run the consumer with {@code auto-offset-reset=latest}, deliberately: the
 * shared dev broker carries events from every earlier test, and some are for symbols that
 * were never persisted -- which settlement treats as an abort-the-batch condition, so
 * {@code earliest} would wedge on them and never reach this test's own events.
 *
 * <p>The consequence is that <b>"latest" is resolved at assignment time</b>. Publish before
 * the group has joined and been assigned its partitions, and the offset it starts from is
 * already past the events just written -- they are never delivered, nothing settles, and
 * the failure surfaces much later as a balance or fill count that never moved.
 *
 * <p>This replaced a fixed {@code Thread.sleep(5_000)}, which was a guess at how long a
 * rebalance takes. It held on a warm laptop and not on a loaded CI runner, which is the
 * worst shape a test failure can have: green locally, red in CI, and pointing at an
 * assertion unrelated to the cause.
 */
public final class ConsumerReadiness {

    private ConsumerReadiness() {
    }

    /**
     * @param timeout how long to allow for the group to join. Generous on purpose -- the
     *                cost of waiting is seconds, the cost of not waiting is a test that
     *                fails for a reason it does not name.
     */
    public static void awaitAssignment(ApplicationContext ctx, Duration timeout)
            throws InterruptedException {
        KafkaListenerEndpointRegistry registry =
                ctx.getBean(KafkaListenerEndpointRegistry.class);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeout.toMillis();

        while (System.currentTimeMillis() < deadline) {
            long assigned = registry.getListenerContainers().stream()
                    .map(container -> container.getAssignedPartitions())
                    .filter(Objects::nonNull)
                    .mapToLong(Collection::size)
                    .sum();
            // Non-empty is enough rather than a specific count: the group is brand new and
            // has exactly one member, so Kafka hands it every partition in one assignment.
            // Checking a count would also mean hardcoding one, and the topic has 8
            // partitions on a dev broker but 1 on a freshly auto-created CI broker.
            if (assigned > 0) {
                // Logged because this is the number the old fixed sleep was guessing at.
                // When a CI run fails here again, the first question is how long it took.
                System.out.printf("ASSIGNMENT_MS %d partitions=%d%n",
                        System.currentTimeMillis() - startedAt, assigned);
                // Assignment fires before the consumer's first fetch; a beat avoids
                // racing it.
                Thread.sleep(500);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("settlement consumer never took any partition "
                + "within " + timeout + " -- it cannot have seen anything published after "
                + "this point");
    }

    /**
     * Blocks until the consumer is provably consuming: assigned, its {@code latest} position
     * resolved, and a batch acknowledged.
     *
     * <p>{@link #awaitAssignment} is not enough on its own. With {@code auto-offset-reset=latest}
     * the fetch position is resolved on the first poll <em>after</em> assignment, so anything
     * published in that gap is behind the resolved position and never delivered. The 500 ms
     * sleep in {@code awaitAssignment} covered the gap on a laptop and not on a loaded CI
     * runner, where the whale test then timed out with zero progress.
     *
     * <p>So this publishes a marker -- an {@code ORDER_ACCEPTED}, which settlement ignores
     * whatever order it names -- keyed by the same symbol as the real events so it lands on
     * the same partition, and waits until the group's committed offset has moved past it.
     * That only happens once the position is resolved and a batch has been acknowledged. If
     * the first marker fell into the gap, it is republished; a marker sent after resolution
     * is always seen, so this converges instead of guessing.
     */
    public static void awaitLive(ApplicationContext ctx, String bootstrap, String group,
                                 String symbolCode, Duration timeout) throws Exception {
        awaitAssignment(ctx, timeout);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + timeout.toMillis();

        Event marker = Event.newBuilder()
                .setSymbol(symbolCode)
                .setSequence(0)
                .setTimestampUs(System.currentTimeMillis() * 1_000)
                .setOrderAccepted(OrderAccepted.newBuilder()
                        .setOrderId(com.google.protobuf.ByteString.copyFrom(
                                Ulid.toBytes(UUID.randomUUID())))
                        .setUserId(0))
                .build();

        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(Map.of(
                     ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                     ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class));
             AdminClient admin = AdminClient.create(
                     Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            int markers = 0;
            while (System.currentTimeMillis() < deadline) {
                RecordMetadata sent = producer.send(new ProducerRecord<>(
                        Topics.SPOT_EVENTS, symbolCode, marker.toByteArray())).get();
                markers++;
                TopicPartition tp = new TopicPartition(Topics.SPOT_EVENTS, sent.partition());

                // Give this marker a few seconds to be consumed before sending another.
                long attemptDeadline = Math.min(deadline, System.currentTimeMillis() + 3_000);
                while (System.currentTimeMillis() < attemptDeadline) {
                    Map<TopicPartition, OffsetAndMetadata> committed = admin
                            .listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
                    OffsetAndMetadata c = committed.get(tp);
                    if (c != null && c.offset() > sent.offset()) {
                        System.out.printf("LIVE_MS %d markers=%d partition=%d%n",
                                System.currentTimeMillis() - startedAt, markers, sent.partition());
                        return;
                    }
                    Thread.sleep(200);
                }
            }
        }
        throw new IllegalStateException("settlement consumer never acknowledged a marker "
                + "within " + timeout + " -- it is assigned but not consuming, so nothing "
                + "published now would be delivered either");
    }

    /**
     * Blocks until the group has acknowledged past {@code offset} on {@code tp}.
     *
     * <p>The only reliable "this batch is done" signal. Settlement commits each order's fills
     * and each balance row's changes concurrently, so any one row can be final while another
     * from the same batch is not -- a test that waits on the taker's balance and then reads the
     * taker's order races the fill that is still committing. The acknowledgement only happens
     * once every wave in the batch has finished.
     *
     * @param progress how far settlement got, for the timeout message: zero progress means the
     *                 events were never delivered, partial progress means the budget was too small
     */
    public static void awaitSettledPast(String bootstrap, String group, TopicPartition tp,
                                        long offset, Duration timeout, Supplier<String> progress)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            while (System.currentTimeMillis() < deadline) {
                OffsetAndMetadata committed = admin.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get().get(tp);
                if (committed != null && committed.offset() > offset) {
                    return;
                }
                Thread.sleep(200);
            }
        }
        throw new AssertionError("settlement did not acknowledge offset " + offset + " within "
                + timeout + " -- progress at timeout: " + progress.get());
    }

    /**
     * Skips when the real spot-settlement is consuming the same broker, as it is under
     * {@code docker compose --profile full}. It settles this test's events too, under its own
     * group and against the same database: two writers racing on one order's rows, which shows
     * up as deadlocks and duplicate keys that fail the batch. CI starts infrastructure only.
     */
    public static void abortIfSettlementAppIsRunning(String bootstrap) throws Exception {
        String appGroup = "spot-settlement"; // application.yml's group-id
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            ConsumerGroupDescription group = admin.describeConsumerGroups(List.of(appGroup))
                    .all().get().get(appGroup);
            if (group != null && !group.members().isEmpty()) {
                Assumptions.abort("spot-settlement is running and would settle this test's events "
                        + "too -- run `docker compose stop spot-settlement` first");
            }
        }
    }
}
