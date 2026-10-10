package com.exchange.spot.matching.kafka;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.contracts.spot.v1.BookSnapshot;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.spot.matching.engine.MatchingEngine;
import com.exchange.spot.matching.snapshot.SnapshotStore;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * The only I/O in the engine. Deserialize, hand to {@link MatchingEngine}, publish what
 * came back, acknowledge.
 *
 * <p><b>Publish before committing the offset -- that ordering is the whole correctness
 * story.</b> Commit first, and a crash in between leaves Kafka saying "done" for an order
 * whose trades never left this process: a fill that happened and moved the book, but that
 * no one downstream ever hears about. Publish first, and a crash just replays the command
 * -- safe, because the engine always produces the same result from the same input.
 *
 * <p>That ordering is also what makes recovery possible: the committed offset means
 * exactly "everything up to here was published," which is the line {@link ReplayTracker}
 * uses to know when to stop replaying quietly and start publishing again.
 *
 * <p>Single-threaded on purpose ({@code concurrency: 1}). The book has no lock, so a
 * market's orders must arrive at it in one, unbroken order, or price-time priority turns
 * into a coin flip.
 */
@Component
public class CommandConsumer implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(CommandConsumer.class);

    private final MatchingEngine engine;
    private final KafkaTemplate<String, byte[]> kafka;
    private final SnapshotStore snapshots;
    private final ReplayTracker replay = new ReplayTracker();

    /** How many commands between snapshots, per partition. */
    private final int snapshotEvery;

    /**
     * The longest a partition may go without a snapshot, no matter how few commands it's
     * seen. A count alone assumes steady traffic, and traffic isn't always steady. Since
     * commands are now kept forever ({@link com.exchange.spot.matching.config.CommandTopicConfig}),
     * a quiet partition that rarely reaches {@link #snapshotEvery} would otherwise have no
     * limit on how much it might need to replay after a restart. This is that limit.
     */
    private final long snapshotMaxIntervalMs;

    /**
     * How many consecutive publish failures {@link #publishAll} tolerates for one command
     * before giving up on this process and exiting, rather than retrying forever.
     *
     * <p>Retrying forever is correct for the DATA -- it guarantees a trade is never lost
     * and never double-executed, see {@link #publishAll}. But because this engine is
     * single-threaded, a stuck retry loop also means the WHOLE instance stops matching
     * anything at all, for every symbol it owns, indefinitely -- and it keeps looking
     * alive the entire time: the process is still running, so nothing about the JVM or
     * the OS notices anything wrong. A liveness heartbeat run on its own thread (the
     * correct way to build one -- see the design discussion this followed) would not
     * catch this either, since it never touches the matching thread it is failing to
     * warn about.
     *
     * <p>So past this many attempts, the engine deliberately kills itself instead of
     * staying silently stuck. That turns an invisible hang into a real process exit,
     * which Kubernetes' restart policy (or any other supervisor) already knows how to
     * react to -- and it does not need to be told anything new. It is also always safe:
     * the stuck command was never acknowledged, so a restart replays it normally through
     * the same snapshot-and-replay path already proven safe, against a fresh book.
     *
     * <p>Not the broker: see the class {@code exit} javadoc for why this only ever kills
     * this one process, never Kafka -- a downstream consumer failing to publish is not
     * reliable evidence the broker itself is unhealthy, and this process has no business
     * touching shared infrastructure it does not own.
     */
    private final int publishMaxAttempts;

    /** Commands processed live since the last snapshot, per partition. */
    private final Map<TopicPartition, Integer> sinceSnapshot = new java.util.HashMap<>();

    /**
     * When each partition last snapshotted, in {@link System#currentTimeMillis()} terms.
     * Seeded lazily to "now" on a partition's first live command, so going live is never
     * itself mistaken for having gone snapshotMaxIntervalMs without one.
     */
    private final Map<TopicPartition, Long> lastSnapshotAt = new java.util.HashMap<>();

    /** The same list the listener below is bound to, for {@link #livePartitions()}. */
    private final List<String> partitions;

    public CommandConsumer(MatchingEngine engine, KafkaTemplate<String, byte[]> kafka,
                           SnapshotStore snapshots,
                           @Value("${engine.partitions}") List<String> partitions,
                           @Value("${engine.snapshot-every:1000}") int snapshotEvery,
                           @Value("${engine.snapshot-max-interval-ms:300000}")
                                   long snapshotMaxIntervalMs,
                           @Value("${engine.publish-max-attempts:30}") int publishMaxAttempts) {
        this.engine = engine;
        this.kafka = kafka;
        this.snapshots = snapshots;
        this.partitions = partitions;
        this.snapshotEvery = snapshotEvery;
        this.snapshotMaxIntervalMs = snapshotMaxIntervalMs;
        this.publishMaxAttempts = publishMaxAttempts;
    }

    /**
     * Rewinds every assigned partition to the beginning so the book can be rebuilt.
     *
     * <p>The offset Kafka would have started from is recorded first: everything before it
     * was already published, so it replays silently, and everything from it onward
     * publishes normally.
     */
    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments,
                                     ConsumerSeekCallback callback) {
        assignments.forEach((partition, startOffset) -> {
            replay.beginReplay(partition, startOffset);

            // A snapshot turns recovery from "replay everything ever" into "replay what
            // has happened since". Without it, restart time grows forever.
            Optional<BookSnapshot> snapshot = snapshots.latestFor(partition.partition());
            if (snapshot.isPresent()) {
                BookSnapshot restored = snapshot.get();
                engine.restore(restored);
                long resumeAt = restored.getOffset() + 1;
                log.info("restored {} from a snapshot at offset {} ({} symbols); "
                                + "replaying from {} and publishing from {}",
                        partition, restored.getOffset(), restored.getSymbolsCount(),
                        resumeAt, startOffset);
                callback.seek(partition.topic(), partition.partition(), resumeAt);
                replay.resumedAt(partition, resumeAt);
            } else {
                log.info("no snapshot for {}; rebuilding from offset 0, publishing from {}",
                        partition, startOffset);
                callback.seekToBeginning(partition.topic(), partition.partition());
                replay.resumedAt(partition, 0);
            }
        });
    }

    /**
     * The partitions whose book is current: assigned, and replayed up to the committed
     * offset. What {@code EngineHeartbeatPublisher} announces, and nothing else reads it.
     * Read from the heartbeat's thread while this one matches; {@link ReplayTracker} holds
     * the set concurrently for that one reader, and nothing of the book itself is exposed.
     */
    public Set<Integer> livePartitions() {
        Set<Integer> live = new TreeSet<>();
        for (String p : partitions) {
            TopicPartition tp = new TopicPartition(Topics.SPOT_COMMANDS, Integer.parseInt(p.trim()));
            if (replay.isLive(tp)) {
                live.add(tp.partition());
            }
        }
        return live;
    }

    @Override
    public void onPartitionsRevoked(java.util.Collection<TopicPartition> partitions) {
        // With static assignment this only fires on shutdown. It would matter under a
        // subscription, where a revoked partition also needs its symbols discarded from
        // the engine -- otherwise a later reassignment restores on top of live state.
        partitions.forEach(replay::forget);
    }

    /**
     * Which partitions this instance owns is set STATICALLY from config, not decided by
     * Kafka's group coordinator.
     *
     * <p>The normal way -- subscribing to a topic -- lets Kafka move a partition to a
     * different instance any time group membership changes, which includes every deploy.
     * That's free for a stateless consumer. Here it isn't: the instance losing the
     * partition is still holding a whole order book it no longer owns, and the instance
     * gaining it can't match anything until it has loaded a snapshot and replayed the
     * log. That's a real outage window, and Kafka -- not you -- decides when it happens.
     *
     * <p>Assigning partitions by hand avoids that entirely: no group coordination, no
     * rebalance, ever. Scaling out becomes a deploy-time decision instead:
     *
     * <pre>
     *   one instance      ENGINE_PARTITIONS=0,1,2,3,4,5,6,7
     *   eight instances   ENGINE_PARTITIONS=0   ENGINE_PARTITIONS=1   ...
     * </pre>
     *
     * <p>Same jar, one environment variable per instance. The cost: Kafka no longer stops
     * two instances from being configured with the same partition by mistake. That
     * becomes a deployment rule to follow, not something the broker enforces for you.
     */
    // Fully qualified: org.apache.kafka.common.TopicPartition is the runtime type used
    // throughout this class, and the bare name should keep meaning that one.
    @KafkaListener(topicPartitions = @org.springframework.kafka.annotation.TopicPartition(
            topic = Topics.SPOT_COMMANDS,
            partitions = "#{'${engine.partitions}'.split(',')}"))
    public void onCommand(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        TopicPartition partition = new TopicPartition(record.topic(), record.partition());
        boolean replaying = replay.isReplaying(partition, record.offset());
        replay.seen(partition, record.offset());

        Command command;
        try {
            command = Command.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            // Unparseable, and retrying will not change that. Acknowledge and move on, or
            // this one message blocks the partition forever.
            log.error("skipping malformed command at {} offset {}",
                    partition, record.offset(), e);
            if (!replaying) {
                ack.acknowledge();
            }
            return;
        }

        List<Event> events = engine.handle(command, record.partition());

        if (replaying) {
            // State rebuilt; the events already reached the topic on the original run.
            // Deliberately no acknowledge — moving the committed offset backwards during
            // a replay would destroy the very boundary this depends on.
            if (record.offset() + 1 == replay.boundaryFor(partition)) {
                log.info("{} rebuilt through offset {}; now live", partition, record.offset());
            }
            return;
        }

        try {
            publishAll(partition, record.offset(), command.getSymbol(), events);
        } catch (InterruptedException e) {
            // Shutting down mid-publish. Do not acknowledge -- this offset was never
            // committed, so a restart replays it normally through the safe snapshot path,
            // against a fresh book. Restoring the flag here, not rethrowing, is deliberate:
            // letting this escape as an unchecked exception would hand it to Spring's
            // listener-level error handling, which is exactly the redelivery-without-a-
            // restart path publishAll's retry loop exists to avoid.
            Thread.currentThread().interrupt();
            log.warn("interrupted publishing {} events for {}; not committing offset {}",
                    events.size(), command.getSymbol(), record.offset(), e);
            return;
        }

        ack.acknowledge();
        maybeSnapshot(partition, record.offset());
    }

    /**
     * Takes a snapshot every N live commands, or every snapshotMaxIntervalMs, whichever
     * happens first -- always after the offset is committed, so a snapshot never
     * describes state whose events haven't reached the topic yet.
     *
     * <p>Each rule covers the other's blind spot. Count alone would starve a quiet
     * partition: the command log is now kept forever, so nothing else limits how long it
     * goes between snapshots, and its worst-case replay would grow without bound. Time
     * alone would over-snapshot a busy one, firing many times a second and paying the
     * full cost of walking every symbol's book ({@link MatchingEngine#snapshot}) each time.
     *
     * <p>Checked here, on the same thread that mutates the book -- never on a separate
     * {@code @Scheduled} timer. {@link com.exchange.spot.matching.book.OrderBook} is
     * deliberately not thread-safe, and a second thread reading it while this one is
     * matching an order is exactly the race that design exists to prevent.
     */
    private void maybeSnapshot(TopicPartition partition, long offset) {
        long now = System.currentTimeMillis();
        int processed = sinceSnapshot.merge(partition, 1, Integer::sum);
        long last = lastSnapshotAt.computeIfAbsent(partition, p -> now);
        boolean due = processed >= snapshotEvery || now - last >= snapshotMaxIntervalMs;
        if (!due) {
            return;
        }
        sinceSnapshot.put(partition, 0);
        lastSnapshotAt.put(partition, now);
        snapshots.write(engine.snapshot(partition.partition(), offset, now * 1_000));
    }

    /** Fixed, short, and simple on purpose: this is not a tight loop -- a send only fails
     *  here after the producer's OWN internal retries (bounded by delivery.timeout.ms,
     *  120s by default) are already exhausted, so failures are minutes apart, not millis. */
    private static final long PUBLISH_RETRY_BACKOFF_MS = 1_000;

    /**
     * Publishes every event and blocks until all of them are acknowledged by the broker --
     * retrying internally, never returning until it succeeds or the process gives up on
     * itself. See {@link #publishMaxAttempts} for why giving up here means exiting rather
     * than looping forever, and {@link #exit} for why exiting is always safe.
     *
     * <p>Retrying HERE, rather than letting a failure escape as an exception, is not a
     * style choice -- it is what stops this command from being reprocessed. {@code
     * engine.handle()} already ran and already mutated the live book before this method is
     * ever called; if a publish failure were instead allowed to propagate out of {@code
     * onCommand}, Spring's listener error handling would redeliver this same record to a
     * STILL-RUNNING process, on its SAME already-mutated book -- not a freshly restored one
     * the way a real crash-and-restart is. {@code OrderBook}'s duplicate-order-id guard
     * only catches that for an order still resting; a market, IOC, or FOK order -- or any
     * order that simply filled completely -- is never added to that index at all, so a
     * second pass would match it for real a second time. Retrying the publish here, using
     * the SAME already-computed {@code events} rather than calling {@code engine.handle()}
     * again, makes that impossible: the book is touched exactly once per command,
     * regardless of how many times its publish has to be retried.
     *
     * <p>Only the specific events that failed are resent on each attempt, never the whole
     * batch -- resending ones that already landed would trade this bug for a smaller
     * version of the same one, just at the level of individual events instead of whole
     * commands.
     */
    private void publishAll(TopicPartition partition, long offset, String symbol,
                            List<Event> events) throws InterruptedException {
        List<byte[]> unsent = new ArrayList<>(events.size());
        for (Event event : events) {
            unsent.add(event.toByteArray());
        }

        for (int attempt = 1; !unsent.isEmpty(); attempt++) {
            // Every send is issued BEFORE any of them is waited on. Waiting inside the loop
            // cost a full broker round trip per event, and one order that crosses five
            // makers emits six -- so the engine spent milliseconds of network time on work
            // the producer batches into a single request.
            List<CompletableFuture<SendResult<String, byte[]>>> pending =
                    new ArrayList<>(unsent.size());
            for (byte[] payload : unsent) {
                pending.add(kafka.send(Topics.SPOT_EVENTS, symbol, payload));
            }

            // Sending several requests before waiting on any of them is still safe for
            // ordering: the producer (enable.idempotence: true) keeps them in order even
            // if the broker has to retry one.
            List<byte[]> stillUnsent = new ArrayList<>();
            Exception lastFailure = null;
            for (int i = 0; i < pending.size(); i++) {
                try {
                    pending.get(i).get();
                } catch (ExecutionException e) {
                    stillUnsent.add(unsent.get(i));
                    lastFailure = e;
                }
            }

            if (stillUnsent.isEmpty()) {
                return;
            }

            if (attempt >= publishMaxAttempts) {
                exit(partition, offset, symbol, stillUnsent.size(), attempt, lastFailure);
                return;   // unreachable once the JVM is actually gone; keeps the compiler happy
            }

            log.error("failed to publish {} of {} events for {} on attempt {}; retrying",
                    stillUnsent.size(), unsent.size(), symbol, attempt, lastFailure);
            unsent = stillUnsent;
            Thread.sleep(PUBLISH_RETRY_BACKOFF_MS);
        }
    }

    /**
     * Ends this process, on purpose, after {@link #publishMaxAttempts} consecutive publish
     * failures for one command.
     *
     * <p>{@code System.exit}, not a thrown exception. An exception here would go through
     * Spring's own listener error handling -- exactly the redelivery-without-a-restart path
     * the rest of this class works to avoid -- and might not end the process at all. This
     * needs to be unconditional: no framework layer gets a chance to catch it, retry it, or
     * decide otherwise.
     *
     * <p>Kills only THIS process, never Kafka. Sustained publish failure is not reliable
     * evidence the broker itself is unhealthy -- it looks identical whether the broker is
     * genuinely down, one topic has a bad config, or the network path from this one pod is
     * broken, and only one of those is actually Kafka's problem. Kafka is also almost
     * certainly shared with other services this engine has never heard of; taking action
     * against it from here would be a blast-radius mismatch, and giving this process the
     * access to do so would be a privileged capability it has no business holding. Its own
     * exit, by contrast, is fully self-contained: Kubernetes' restart policy already knows
     * what to do with a process that exits non-zero, and the command that triggered this
     * was never acknowledged, so a restart replays it normally, against a fresh book,
     * through the same snapshot-and-replay path already proven safe.
     */
    private void exit(TopicPartition partition, long offset, String symbol,
                      int stillUnsentCount, int attempts, Exception lastFailure) {
        log.error("giving up on publishing {} events for {} at {} offset {} after {} "
                        + "consecutive attempts; exiting so the process can be restarted "
                        + "cleanly -- this command was never acknowledged, so it will replay "
                        + "safely on the next start",
                stillUnsentCount, symbol, partition, offset, attempts, lastFailure);
        System.exit(1);
    }
}
