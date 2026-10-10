package com.exchange.spot.symbol;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * One group per instance, like {@code SettlementConsumerGroup}: every process must hear every
 * heartbeat to make its own decision.
 *
 * <p>Named, because the listener finds it by name in SpEL and an {@code @Import}ed class (how
 * spot-copy brings it in) is otherwise registered under its full class name.
 */
@Component("enginePresenceConsumerGroup")
public class EnginePresenceConsumerGroup {

    private final String id;

    public EnginePresenceConsumerGroup(@Value("${spring.application.name}") String app) {
        this.id = app + "-engine-status-" + UUID.randomUUID();
    }

    public String getId() {
        return id;
    }
}
