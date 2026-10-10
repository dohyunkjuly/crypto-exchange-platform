package com.exchange.wallet.balance;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.BalanceChanged;
import com.exchange.contracts.spot.v1.Settlement;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Announces a balance change on {@code exchange.spot.settlements.v1}, after it commits.
 *
 * <p>Separate from {@link BalanceService} so that class stays about money -- but called from
 * inside it, because <b>the write is the only place that cannot be forgotten</b>. Balances move
 * in settlement, in both apps when an order locks funds, and in whatever credits a deposit
 * later: emitting from each would be four places to remember and one to miss.
 *
 * <p><b>After commit, never before.</b> {@code save()} stages a write in a transaction that may
 * roll back, and callers nest. Publishing at save time would announce balances that never
 * existed, leaving a client rendering a wrong number until that account's next change -- a
 * dropped message leaves a panel stale, a phantom one leaves it wrong, for just as long.
 *
 * <p><b>Off the calling thread</b>, because {@code BalanceService} holds
 * {@code SELECT ... FOR UPDATE} on the most contended rows here and the callback would
 * otherwise run on the caller's -- a slow broker would delay the next batch.
 *
 * <p><b>A failure is logged and dropped</b>, which this topic allows (AGENTS.md) and is what
 * makes asynchrony safe at all.
 */
@Component
public class BalanceChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(BalanceChangePublisher.class);

    /**
     * How many pending announcements to hold before discarding.
     *
     * <p>Deep enough to absorb a burst, shallow enough that a broker outage does not turn
     * into unbounded memory. When it fills, the <b>oldest</b> pending message is dropped:
     * these carry absolute balances rather than deltas, so the newest is the truest and an
     * older one is worth strictly less than the one behind it.
     */
    private static final int QUEUE_CAPACITY = 10_000;

    private final KafkaTemplate<String, byte[]> kafka;

    /**
     * One thread, deliberately. It keeps this instance's own announcements in the order they
     * committed, which is the cheapest thing that can be done about ordering -- across
     * processes nothing can, which is why {@code BalanceChanged} carries the row's version.
     */
    private final ThreadPoolExecutor publisher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "balance-change-publisher");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public BalanceChangePublisher(KafkaTemplate<String, byte[]> kafka) {
        this.kafka = kafka;
    }

    /**
     * Registers this balance to be announced once the surrounding transaction commits.
     *
     * <p>Reads nothing now. The values are taken inside the callback because that is the only
     * point they are final: Hibernate increments {@code @Version} when it flushes the UPDATE,
     * so capturing {@code getVersion()} here would publish the number the row had
     * <em>before</em> the write -- and every consumer compares on exactly that field.
     */
    void publishAfterCommit(Balance balance) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to hang off. Cannot happen from BalanceService, whose every
            // mutating method is @Transactional, and not worth guessing at if it ever does:
            // without a commit to wait for there is nothing truthful to announce.
            log.warn("no active transaction; not announcing a balance change for user {}",
                    balance.getUserId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(balance.getUserId(), balance.getAssetId(), balance.getAvailable(),
                        balance.getLocked(), balance.getVersion());
            }
        });
    }

    /**
     * Hands six primitives to the executor and does nothing else here.
     *
     * <p>Building the message used to happen on this thread, which measurement showed was not
     * free: the benchmarks whose shape touches a distinct balance row per trade -- so one
     * announcement per row rather than one per batch -- lost around ten percent, while the
     * shapes that batch were unchanged. Protobuf construction is allocation, and allocation
     * on the thread that just held a row lock is the thing this class exists to avoid. The
     * timestamp moves too: it is when the change was published, and reading a clock is as
     * much work as the builder.
     */
    private void submit(Long userId, Long assetId, long available, long locked, long version) {
        publisher.execute(() -> {
            try {
                Settlement message = Settlement.newBuilder()
                        .setUserId(userId)
                        .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                        .setBalanceChanged(BalanceChanged.newBuilder()
                                .setAssetId(assetId)
                                .setFree(available)
                                .setLocked(locked)
                                .setVersion(version))
                        .build();
                // Keyed by user id: everything about one account shares a partition, so a
                // fill and the balance change it caused stay in the order they happened.
                kafka.send(Topics.SPOT_SETTLEMENTS, Long.toString(userId),
                        message.toByteArray());
            } catch (RuntimeException e) {
                log.warn("could not announce a balance change for user {} asset {}; the "
                        + "balance itself is committed and readable over REST", userId, assetId, e);
            }
        });
    }

    /** Stops accepting work on shutdown. Nothing is flushed: a pending announcement describes
     *  a balance already committed, and a client reconnecting refetches anyway. */
    @PreDestroy
    void shutdown() {
        publisher.shutdownNow();
    }
}
