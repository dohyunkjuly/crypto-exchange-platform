package com.exchange.spot.matching.kafka;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.TopicPartition;

/**
 * Decides, per partition, whether the engine is still rebuilding its book or has caught up
 * to live traffic.
 *
 * <p>The book lives in RAM and comes entirely from the command log, so a restart has to
 * replay that log from the start -- resuming from a committed offset would leave the book
 * empty while every downstream system still thinks those resting orders exist.
 *
 * <p>Replaying naively would re-publish every event and duplicate trades that already
 * happened. So the replay runs <b>silently</b> instead: commands go through the engine to
 * rebuild its state, and whatever events they produce are just thrown away.
 *
 * <p>The line between silent and live is the <b>committed offset</b> -- which is exactly
 * why {@code CommandConsumer} always publishes before it commits. Only committing after
 * the broker confirms the events means the committed offset can be trusted to mean
 * precisely "everything up to here was already published":
 *
 * <pre>
 *   offset 0 ....... committed ....... end
 *   |-- replay silently --|-- publish live --|
 * </pre>
 *
 * <p>A command that was matched but whose events never made it to Kafka sits at or after
 * the committed offset, so on replay it both reprocesses <em>and</em> publishes -- which is
 * exactly the crash this ordering is built to survive.
 */
final class ReplayTracker {

    private final Map<TopicPartition, Long> liveFrom = new ConcurrentHashMap<>();

    /**
     * Partitions whose replay has reached the boundary, so their book is current and their
     * heartbeat may go out. Membership is decided on the consumer thread and read from the
     * heartbeat's; the set is concurrent for that reason alone.
     */
    private final Set<TopicPartition> live = ConcurrentHashMap.newKeySet();

    /**
     * Records where publishing should resume for a partition, and reports whether any
     * silent replay is needed at all.
     *
     * @param committedOffset the position the consumer would otherwise have started from
     */
    boolean beginReplay(TopicPartition partition, long committedOffset) {
        liveFrom.put(partition, committedOffset);
        live.remove(partition);
        return committedOffset > 0;
    }

    /**
     * Called with each record the consumer handles: live once a record at or past the
     * boundary has been seen, or the last replayed one. A partition that resumes exactly at
     * its boundary never sees a replayed record, so {@link #resumedAt} covers that case.
     */
    void seen(TopicPartition partition, long offset) {
        if (offset + 1 >= boundaryFor(partition)) {
            live.add(partition);
        }
    }

    /** Where the consumer will read from after the snapshot restore; nothing to replay means live now. */
    void resumedAt(TopicPartition partition, long offset) {
        if (offset >= boundaryFor(partition)) {
            live.add(partition);
        }
    }

    /** Replay finished: the book is as current as the log, and the partition may be announced. */
    boolean isLive(TopicPartition partition) {
        return live.contains(partition);
    }

    /**
     * True while this record is being replayed to rebuild state, so its events must not
     * be published.
     */
    boolean isReplaying(TopicPartition partition, long offset) {
        Long boundary = liveFrom.get(partition);
        return boundary != null && offset < boundary;
    }

    /** How far a partition still has to go, for logging. */
    long boundaryFor(TopicPartition partition) {
        return liveFrom.getOrDefault(partition, 0L);
    }

    void forget(TopicPartition partition) {
        liveFrom.remove(partition);
        live.remove(partition);
    }
}
