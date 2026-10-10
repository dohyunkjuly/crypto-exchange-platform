package com.exchange.spot.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes what {@link CommandOutboxPublisher} could not, or chose not to: rows still
 * unpublished after {@code minAge}, oldest first. Runs on every instance of every app that
 * places orders, uncoordinated: two instances may send the same row, and the engine skips
 * the second copy. Cheaper than a lock, which would have to be held across the publish.
 *
 * <p>Order within a market is kept: after a row for a market fails, the rest of that
 * market's rows are left for the next pass rather than sent ahead of it. Other markets carry
 * on -- one market's broker trouble is not another's.
 */
@Component
public class CommandOutboxSweeper {

    private static final Logger log = LoggerFactory.getLogger(CommandOutboxSweeper.class);

    private final SpotCommandOutboxRepository rows;
    private final CommandOutboxService outbox;
    private final Duration minAge;
    private final int batch;
    private final Duration keepPublished;

    public CommandOutboxSweeper(SpotCommandOutboxRepository rows, CommandOutboxService outbox,
                                // Younger than this may still be the publisher's: just past
                                // CommandOutboxPublisher.ACK_TIMEOUT_SECONDS.
                                @Value("${exchange.spot.outbox.min-age-ms:6000}") long minAgeMs,
                                @Value("${exchange.spot.outbox.batch:500}") int batch,
                                @Value("${exchange.spot.outbox.keep-published-ms:3600000}")
                                        long keepPublishedMs) {
        this.rows = rows;
        this.outbox = outbox;
        this.minAge = Duration.ofMillis(minAgeMs);
        this.batch = batch;
        this.keepPublished = Duration.ofMillis(keepPublishedMs);
    }

    @Scheduled(fixedDelayString = "${exchange.spot.outbox.sweep-ms:1000}")
    public void sweep() {
        Instant now = Instant.now();
        List<SpotCommandOutbox> due = rows.findPending(now.minus(minAge), PageRequest.of(0, batch));
        if (!due.isEmpty()) {
            publishInOrder(due);
        }
        rows.deletePublishedBefore(now.minus(keepPublished));
    }

    /** @return how many were published; exposed for the test */
    int publishInOrder(List<SpotCommandOutbox> due) {
        Set<String> stalled = new HashSet<>();
        int published = 0;
        for (SpotCommandOutbox row : due) {
            if (stalled.contains(row.getSymbol())) {
                continue;
            }
            if (outbox.publish(row)) {
                published++;
            } else {
                stalled.add(row.getSymbol());
            }
        }
        log.warn("outbox sweep: {} of {} overdue commands published{}", published, due.size(),
                stalled.isEmpty() ? "" : "; stalled on " + stalled);
        return published;
    }
}
