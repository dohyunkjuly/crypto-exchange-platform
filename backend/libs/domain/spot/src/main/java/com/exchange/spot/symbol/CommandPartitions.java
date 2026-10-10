package com.exchange.spot.symbol;

import com.exchange.common.kafka.Topics;
import com.exchange.spot.order.exception.MarketUnavailableException;
import java.util.List;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Whether each market's configured command partition exists on the commands topic.
 *
 * <p>{@code spot_symbols.command_partition} is operator data and the topic's partition count is
 * broker state, and nothing else ties them. A market pointed past the last partition can never
 * take an order, so this catches it twice: at startup, refusing to start with the markets named,
 * and before each order, for a market listed while the app runs.
 *
 * <p>Reads the count through the admin API, never the producer: asking the producer about a
 * topic that does not exist yet makes the broker auto-create it with one partition. Partitions
 * only ever grow, so a remembered count is re-read before refusing a market beyond it.
 */
@Component
public class CommandPartitions {

    private static final Logger log = LoggerFactory.getLogger(CommandPartitions.class);

    private final SpotSymbolRepository spotSymbols;
    private final KafkaAdmin kafkaAdmin;

    /** Zero until read; a partition below it needs no broker call. */
    private volatile int knownCount;

    public CommandPartitions(SpotSymbolRepository spotSymbols, KafkaAdmin kafkaAdmin) {
        this.spotSymbols = spotSymbols;
        this.kafkaAdmin = kafkaAdmin;
    }

    /** @throws IllegalStateException naming every market past the last partition -- which,
     *          thrown here, stops the app from starting */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyEveryMarket() {
        Integer count = readCount(true);
        if (count == null) {
            return;
        }
        List<String> misplaced = spotSymbols.findAll().stream()
                .filter(market -> market.getCommandPartition() >= count)
                .map(market -> market.getSymbol() + " -> " + market.getCommandPartition())
                .toList();
        if (!misplaced.isEmpty()) {
            throw new IllegalStateException(Topics.SPOT_COMMANDS + " has " + count
                    + " partitions (0-" + (count - 1) + "), so these markets could never take an order: "
                    + misplaced + ". Fix spot_symbols.command_partition, then start again.");
        }
        log.info("every market's command partition exists on {} ({} partitions)",
                Topics.SPOT_COMMANDS, count);
    }

    /** @throws MarketUnavailableException the market's partition does not exist, even re-read */
    public void requireExists(SpotSymbol market) {
        int partition = market.getCommandPartition();
        if (partition < knownCount) {
            return;
        }
        Integer count = readCount(false);
        if (count == null || partition < count) {
            return; // Unknown: the publish itself is the last word.
        }
        log.error("{} is configured for command partition {}, but {} has only {} -- it takes no "
                        + "orders until spot_symbols.command_partition is fixed",
                market.getSymbol(), partition, Topics.SPOT_COMMANDS, count);
        throw new MarketUnavailableException(market.getSymbol());
    }

    /** Null when the topic does not exist yet or the broker cannot say. */
    private Integer readCount(boolean atStartup) {
        try {
            TopicDescription topic = kafkaAdmin.describeTopics(Topics.SPOT_COMMANDS)
                    .get(Topics.SPOT_COMMANDS);
            knownCount = topic.partitions().size();
            return knownCount;
        } catch (KafkaException e) {
            if (atStartup) {
                String why = causedBy(e, UnknownTopicOrPartitionException.class)
                        ? "does not exist yet (the matching engine creates it)"
                        : "could not be read";
                log.warn("{} {}; market partitions will be checked per order instead",
                        Topics.SPOT_COMMANDS, why, e);
            }
            return null;
        }
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
