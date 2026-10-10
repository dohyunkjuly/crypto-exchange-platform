package com.exchange.marketdata.ingest;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.DepthLevelChanged;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.marketdata.depth.BookSide;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes level changes to {@code exchange.spot.depth.v1}, keyed by symbol.
 *
 * <p>After the book is written, never before: a change on the wire that the store does not
 * hold would let a client resync from a REST snapshot that is behind the feed, which the
 * sequence check cannot detect. Fire-and-forget, logged on failure and never retried into the
 * write path -- the topic is allowed to drop, and a stuck write is the worse failure.
 */
@Component
public class DepthUpdatePublisher implements LevelChangeSink {

    private static final Logger log = LoggerFactory.getLogger(DepthUpdatePublisher.class);

    private final KafkaTemplate<String, byte[]> kafka;

    public DepthUpdatePublisher(KafkaTemplate<String, byte[]> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(List<LevelChange> changes) {
        for (LevelChange c : changes) {
            DepthLevelChanged message = DepthLevelChanged.newBuilder()
                    .setSymbol(c.symbol()).setSequence(c.sequence())
                    .setSide(c.side() == BookSide.BID ? Side.SIDE_BUY : Side.SIDE_SELL)
                    .setPrice(c.price()).setQuantity(c.quantity())
                    .build();
            kafka.send(Topics.SPOT_DEPTH, c.symbol(), message.toByteArray()).whenComplete((r, e) -> {
                if (e != null) {
                    log.warn("could not publish a depth change for {} at sequence {}; a client resyncs", c.symbol(), c.sequence(), e);
                }
            });
        }
    }
}
