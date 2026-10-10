package com.exchange.spot.stream;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer group id for this process's private user WebSocket -- one per instance,
 * for the reason {@code MarketWsConsumerGroup} spells out in full.
 *
 * <p>The argument is the same but the stakes are higher. A browser connected to instance B
 * belongs to a user whose orders hash to a partition instance A owns, so every instance must
 * read the whole topic. Share one group and each instance gets a slice: a user would see
 * their order updates only when their own events happened to land on the partition their
 * connection's instance was assigned. No error, no failed health check -- just a panel that
 * updates for some orders and not others, which is far harder to diagnose than one that
 * never updates at all.
 *
 * <p>A separate group from the market socket's, rather than sharing one consumer: the two
 * take different slices of the same topic and have different lifetimes, and a single
 * listener feeding both would couple a public feed's failures to a private one's.
 */
@Component
public class UserWsConsumerGroup {

    private static final Logger log = LoggerFactory.getLogger(UserWsConsumerGroup.class);

    private final String id;

    /** Prefixed with the app's name so an operator can tell whose instance a group belongs to. */
    public UserWsConsumerGroup(@Value("${spring.application.name}") String app) {
        this.id = app + "-user-ws-" + UUID.randomUUID();
        log.info("user WebSocket consumer group for this instance: {}", id);
    }

    public String getId() {
        return id;
    }
}
