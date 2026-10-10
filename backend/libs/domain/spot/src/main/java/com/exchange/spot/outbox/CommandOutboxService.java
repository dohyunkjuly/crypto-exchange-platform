package com.exchange.spot.outbox;

import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Command;
import com.exchange.spot.symbol.SpotSymbol;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * How the API's commands reach the engine: written to {@code spot_command_outbox} inside the
 * caller's transaction, published after it commits.
 *
 * <p>Not published inside the transaction, which is what this replaced. A transaction can
 * roll back its rows; it cannot un-send a Kafka record. A publish that timed out rolled the
 * order back while the producer went on retrying in the background and landed the record
 * anyway -- an order the engine traded with no row and no funds locked, and a settlement
 * partition stuck on the row it could not find. Writing the command as a row makes every
 * write in the transaction a database write: they commit together or not at all, and Kafka
 * is never told about an order the database does not have.
 * docs/reference/delivery-guarantees.md.
 *
 * <p><b>The row is the promise.</b> A request answers 202 as soon as its transaction has
 * committed: from then the command will reach the engine, whatever happens to this process or
 * to Kafka. The caller {@link #handOff}s the row {@link #enqueue} gave it, once its
 * transaction has ended, and {@link CommandOutboxPublisher} sends it within milliseconds, off
 * the request thread. Whatever that does not get acknowledged is left unstamped, and
 * {@link CommandOutboxSweeper} sends it, oldest first. A row sent by both reaches the engine
 * twice with one {@code command_id}, and the engine skips the second.
 *
 * <p><b>Ended, not merely committed.</b> An {@code afterCommit} hook still holds the
 * transaction's database connection, and whatever runs there that needs one of its own takes a
 * second. Ten requests each holding one connection and waiting for another is every connection
 * in the pool held by a request that can never finish: the API stopped for thirty seconds the
 * first time ten orders arrived together. So the hand-off is the caller's to do, after the
 * connection has gone back.
 *
 * <p>How a cancel is kept from overtaking the place it cancels: {@link CommandOutboxPublisher}.
 */
@Service
public class CommandOutboxService {

    private static final Logger log = LoggerFactory.getLogger(CommandOutboxService.class);

    /** How long the immediate attempt waits. Past this the row is the sweeper's. */
    static final int PUBLISH_TIMEOUT_SECONDS = 5;

    private final SpotCommandOutboxRepository rows;
    private final KafkaTemplate<String, byte[]> kafka;

    private final CommandOutboxPublisher publisher;

    public CommandOutboxService(SpotCommandOutboxRepository rows, KafkaTemplate<String, byte[]> kafka,
                         CommandOutboxPublisher publisher) {
        this.rows = rows;
        this.kafka = kafka;
        this.publisher = publisher;
    }

    /**
     * Writes the command as a row in the caller's transaction.
     *
     * @param orderId the order this command is about; null for a cancel-all
     * @return the row, for the caller to {@link #handOff} once its transaction has ended
     */
    public SpotCommandOutbox enqueue(SpotSymbol market, UUID orderId, Long userId, Command command) {
        SpotCommandOutbox row = new SpotCommandOutbox(
                Ulid.toUuid(command.getCommandId().toByteArray()),
                market.getSymbol(), market.getCommandPartition(), orderId, userId,
                command.toByteArray());
        rows.save(row);

        boolean behindAnother = orderId != null
                ? rows.hasOtherPendingForOrder(orderId, row.getId())
                : rows.hasOtherPendingForUser(userId, row.getId());
        if (behindAnother) {
            row.markBehindAnother();
        }
        return row;
    }

    /**
     * Gives a committed row to the publisher. Inside a transaction it does nothing: the row is
     * not committed yet, and a command Kafka has for an order the database may still roll
     * back is the very thing this class exists to prevent. The sweeper sends it after the commit.
     */
    public void handOff(SpotCommandOutbox row) {
        if (row != null && !TransactionSynchronizationManager.isActualTransactionActive()) {
            publisher.submit(row);
        }
    }

    /** Is something about this order still on its way to the engine? A cancel or amend that
     *  went straight to Kafka would overtake it. */
    public boolean hasPendingFor(UUID orderId) {
        return rows.hasPendingForOrder(orderId);
    }

    /** Is anything of this user's still on its way? What a cancel-all would overtake. */
    public boolean hasPendingForUser(Long userId) {
        return rows.hasPendingForUser(userId);
    }

    /**
     * The sweeper's way: one row, blocking until the broker has it, then stamped. Never throws.
     *
     * @return whether the broker acknowledged it
     */
    boolean publish(SpotCommandOutbox row) {
        try {
            kafka.send(Topics.SPOT_COMMANDS, row.getCommandPartition(), row.getSymbol(), row.getPayload())
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("interrupted publishing command {} for {}; left for the sweeper",
                    row.getId(), row.getSymbol());
            return false;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            log.warn("failed to publish command {} for {}; left for the sweeper",
                    row.getId(), row.getSymbol(), e);
            return false;
        }
        // If THIS fails the row stays pending and the sweeper re-sends a command the
        // engine has already seen, which it skips. Never the other way round.
        rows.markPublished(row.getId(), Instant.now());
        return true;
    }
}
