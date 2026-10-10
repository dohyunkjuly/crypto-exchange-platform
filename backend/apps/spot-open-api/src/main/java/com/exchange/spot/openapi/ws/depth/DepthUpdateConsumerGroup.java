package com.exchange.spot.openapi.ws.depth;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One consumer group per instance, for the reason the shared {@code MarketWsConsumerGroup} gives:
 *  a subscriber on this instance may watch a market whose partition another instance owns. */
@Component
public class DepthUpdateConsumerGroup {

    private static final Logger log = LoggerFactory.getLogger(DepthUpdateConsumerGroup.class);

    private final String id;

    public DepthUpdateConsumerGroup(@Value("${spring.application.name}") String app) {
        this.id = app + "-depth-" + UUID.randomUUID();
        log.info("depth update consumer group for this instance: {}", id);
    }

    public String getId() {
        return id;
    }
}
