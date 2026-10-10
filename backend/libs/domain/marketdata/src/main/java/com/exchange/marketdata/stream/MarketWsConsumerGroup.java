package com.exchange.marketdata.stream;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer group id for this process's market WebSocket -- <b>one per instance</b>,
 * the opposite of every other consumer here.
 *
 * <p>Settlement and market-data each use one fixed group, so Kafka divides the partitions and
 * every event is handled once: what a writer needs. This needs the opposite. A browser on
 * instance B may watch a symbol whose partition instance A owns, so <b>every instance must see
 * every event</b>. One shared group would silently freeze the charts of every user whose symbol
 * landed elsewhere -- no error, no failed health check, just a frontend that looks broken.
 *
 * <p>Generated in Java rather than {@code ${random.uuid\}} in application.yml: Spring's random
 * property source yields a new value per lookup, so anything else reading it would see a
 * different id than the consumer joined with.
 *
 * <p>The id changes on restart, which is correct. The stream is live-only -- it starts at
 * {@code latest}, and a reconnecting browser re-fetches over REST -- so committed offsets for a
 * group nobody resumes are meaningless and expire on their own.
 */
@Component
public class MarketWsConsumerGroup {

    private static final Logger log = LoggerFactory.getLogger(MarketWsConsumerGroup.class);

    private final String id;

    /** Prefixed with the app's name so an operator can tell whose instance a group belongs to. */
    public MarketWsConsumerGroup(@Value("${spring.application.name}") String app) {
        this.id = app + "-ws-" + UUID.randomUUID();
        log.info("market WebSocket consumer group for this instance: {}", id);
    }

    public String getId() {
        return id;
    }
}
