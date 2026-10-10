package com.exchange.spot.outbox;

import com.exchange.common.kafka.Topics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends committed outbox rows to Kafka, off the request thread.
 *
 * <p>A request answers 202 once its row is committed and hands the row here. Waiting for the
 * broker on the request thread cost a thread per order for the length of a Kafka round trip: a
 * hundred orders at once was a hundred threads doing nothing but waiting.
 *
 * <p><b>A lane per partition, a batch at a time.</b> A market's commands all go to one
 * partition, so they all pass through one lane, in the order they were handed in. A lane sends a
 * batch, waits for every acknowledgement, stamps the acknowledged rows in one update, and only
 * then takes the next batch. Nothing in a later batch is sent before the batch ahead of it is
 * settled -- which is what lets the rule below be a single read rather than a wait.
 *
 * <p><b>A command never overtakes an older one for the same order.</b> A cancel that reached
 * the engine before the place it cancels is answered with silence, and the order then rests
 * with its owner believing it gone. Two things keep that from happening. A batch is cut where
 * an order appears a second time, so a place and its cancel are never in flight together. And a
 * row that had an older sibling unpublished when it was written is sent only if that sibling
 * has been stamped since; if not -- it failed, or another instance has it -- both are left to
 * {@link CommandOutboxSweeper}, which sends in {@code created_at} order.
 *
 * <p>Whatever is not acknowledged is simply left: unstamped, so the sweeper sends it. That
 * includes a lane too full to take a row, and everything in memory when the process dies. A
 * row sent twice reaches the engine with one {@code command_id}, and the engine skips the second.
 */
@Component
public class CommandOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(CommandOutboxPublisher.class);

    /** How long a batch waits for its acknowledgements. Past this its rows are the sweeper's. */
    static final int ACK_TIMEOUT_SECONDS = 5;

    private final SpotCommandOutboxRepository rows;
    private final KafkaTemplate<String, byte[]> kafka;
    private final int batchMax;
    private final List<BlockingQueue<SpotCommandOutbox>> lanes = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running = true;

    /** @param laneCount zero publishes on the calling thread, which is what a unit test wants */
    public CommandOutboxPublisher(SpotCommandOutboxRepository rows, KafkaTemplate<String, byte[]> kafka,
                                  @Value("${exchange.spot.outbox.lanes:8}") int laneCount,
                                  @Value("${exchange.spot.outbox.lane-capacity:50000}") int laneCapacity,
                                  @Value("${exchange.spot.outbox.batch-max:500}") int batchMax) {
        this.rows = rows;
        this.kafka = kafka;
        this.batchMax = batchMax;
        for (int i = 0; i < laneCount; i++) {
            lanes.add(new LinkedBlockingQueue<>(laneCapacity));
        }
    }

    @PostConstruct
    void start() {
        for (int i = 0; i < lanes.size(); i++) {
            BlockingQueue<SpotCommandOutbox> lane = lanes.get(i);
            Thread thread = new Thread(() -> drain(lane), "outbox-lane-" + i);
            thread.setDaemon(true);
            thread.start();
            threads.add(thread);
        }
    }

    /** Whatever is still queued is committed and unstamped, so the sweeper has it. */
    @PreDestroy
    void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
    }

    /**
     * Takes a committed row to be sent. Never blocks and never throws: the caller has already
     * answered for the order.
     *
     * @return false if the lane is full; the row stays unstamped and the sweeper sends it
     */
    public boolean submit(SpotCommandOutbox row) {
        if (lanes.isEmpty()) {
            publishBatch(List.of(row));
            return true;
        }
        boolean taken = lanes.get(Math.floorMod(row.getCommandPartition(), lanes.size())).offer(row);
        if (!taken) {
            log.warn("outbox lane for partition {} is full; command {} is left for the sweeper",
                    row.getCommandPartition(), row.getId());
        }
        return taken;
    }

    private void drain(BlockingQueue<SpotCommandOutbox> lane) {
        while (running) {
            try {
                publishBatch(nextBatch(lane));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // The database, most likely. The rows are unstamped, so nothing is lost.
                log.error("an outbox lane could not settle a batch; its commands are left for the sweeper", e);
            }
        }
    }

    /** Waits for one row, then takes what else is waiting -- up to the limit, and stopping short
     *  of a second command for an order already in the batch. */
    List<SpotCommandOutbox> nextBatch(BlockingQueue<SpotCommandOutbox> lane) throws InterruptedException {
        List<SpotCommandOutbox> batch = new ArrayList<>();
        Set<Object> subjects = new HashSet<>();
        SpotCommandOutbox row = lane.take();
        while (true) {
            batch.add(row);
            subjects.add(subjectOf(row));
            SpotCommandOutbox next = lane.peek();
            if (next == null || batch.size() >= batchMax || subjects.contains(subjectOf(next))) {
                return batch;
            }
            row = lane.poll();
        }
    }

    /** What two commands must share to be ordered against each other: the order, or for a
     *  cancel-all, which names none, the user. */
    private static Object subjectOf(SpotCommandOutbox row) {
        return row.getOrderId() != null ? row.getOrderId() : "user:" + row.getUserId();
    }

    /** Sends the batch, waits for every acknowledgement, stamps what was acknowledged.
     *  @return how many were acknowledged */
    int publishBatch(List<SpotCommandOutbox> batch) {
        Map<SpotCommandOutbox, CompletableFuture<?>> sent = new LinkedHashMap<>();
        for (SpotCommandOutbox row : batch) {
            if (row.isBehindAnother() && olderStillUnpublished(row)) {
                continue;
            }
            try {
                sent.put(row, kafka.send(Topics.SPOT_COMMANDS, row.getCommandPartition(),
                        row.getSymbol(), row.getPayload()));
            } catch (RuntimeException e) {
                log.warn("could not send command {} for {}; left for the sweeper",
                        row.getId(), row.getSymbol(), e);
            }
        }

        List<UUID> acknowledged = new ArrayList<>(sent.size());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ACK_TIMEOUT_SECONDS);
        for (Map.Entry<SpotCommandOutbox, CompletableFuture<?>> one : sent.entrySet()) {
            try {
                one.getValue().get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                acknowledged.add(one.getKey().getId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("command {} for {} was not acknowledged; left for the sweeper",
                        one.getKey().getId(), one.getKey().getSymbol(), e);
            }
        }
        // If THIS fails the rows stay unstamped and the sweeper re-sends commands the engine
        // has already seen, which it skips. Never the other way round.
        if (!acknowledged.isEmpty()) {
            rows.markPublishedAll(acknowledged, Instant.now());
        }
        return acknowledged.size();
    }

    private boolean olderStillUnpublished(SpotCommandOutbox row) {
        return row.getOrderId() != null
                ? rows.hasOlderPendingForOrder(row.getOrderId(), row.getCreatedAt(), row.getId())
                : rows.hasOlderPendingForUser(row.getUserId(), row.getCreatedAt(), row.getId());
    }
}
