package com.exchange.spot.stream;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer group id for this process's settlement reader -- one per instance, for the
 * reason {@code MarketWsConsumerGroup} sets out in full.
 *
 * <p>Separate from {@link UserWsConsumerGroup} because it reads a different topic. A group id is
 * scoped to the topics a consumer subscribes to, so sharing one across two subscriptions would
 * make the two listeners' rebalances affect each other for no benefit.
 */
@Component
public class SettlementConsumerGroup {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumerGroup.class);

    private final String id;

    /** Prefixed with the app's name so an operator can tell whose instance a group belongs to. */
    public SettlementConsumerGroup(@Value("${spring.application.name}") String app) {
        this.id = app + "-settlements-" + UUID.randomUUID();
        log.info("settlement consumer group for this instance: {}", id);
    }

    public String getId() {
        return id;
    }
}
