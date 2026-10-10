package com.exchange.marketdata.stream;

import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Event;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code exchange.spot.events.v1} purely to push it at browsers.
 *
 * <p>The third independent consumer of this topic, and the only read-only one.
 * {@code spot-settlement} moves money from it and {@code market-data} writes candles from
 * it; this one writes nothing anywhere. That is why it can afford to be careless in ways
 * neither of them can -- a dropped event here means one trade missing from a chart until
 * the next REST refresh, not a balance that is wrong.
 *
 * <p><b>Never blocks and never retries.</b> Delivery to sockets happens on this listener
 * thread, so anything slow here delays delivery for every viewer on this instance;
 * {@link MarketWsHandler} therefore buffers per connection and drops stragglers rather
 * than making this thread wait. There is no unacknowledged-batch redelivery of the kind
 * {@code market-data} relies on either: replaying a trade from a minute ago would print a
 * stale price onto a live chart, which is worse than never having sent it.
 *
 * <p>Only {@code TradeExecuted} is forwarded, on the {@code trade} channel. Accepts,
 * rejects and cancels move no price and no volume, so nothing on a chart changes because of
 * them. Depth is its own channel and reaches sockets a different way -- see
 * {@code DepthPublisher}, which polls the projection rather than folding this stream, so
 * every instance serves the same book.
 */
@Component
public class MarketWsConsumer {

    private static final Logger log = LoggerFactory.getLogger(MarketWsConsumer.class);

    private final MarketFanout sockets;

    public MarketWsConsumer(MarketFanout sockets) {
        this.sockets = sockets;
    }

    /**
     * {@code groupId} is a SpEL reference rather than a property so it resolves exactly
     * once, to the per-instance id -- see {@link MarketWsConsumerGroup} for why one group per
     * process is required here and forbidden everywhere else.
     *
     * <p>{@code auto.offset.reset=latest} is set on the listener rather than in
     * application.yml because it contradicts the app-wide default on purpose: a live feed
     * has no use for history. A brand-new group starting at {@code earliest} would replay
     * the entire topic into somebody's chart at startup.
     */
    @KafkaListener(topics = Topics.SPOT_EVENTS,
            groupId = "#{@marketWsConsumerGroup.id}",
            properties = {"auto.offset.reset=latest"})
    public void onEvent(ConsumerRecord<String, byte[]> record) {
        Event event;
        try {
            event = Event.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            log.error("skipping malformed event at partition {} offset {}",
                    record.partition(), record.offset(), e);
            return;
        }
        if (event.getPayloadCase() != Event.PayloadCase.TRADE_EXECUTED) {
            return;
        }
        sockets.broadcast(Subscription.of(TradeChannel.NAME, event.getSymbol()),
                WsMessage.push(TradeChannel.NAME, TradeData.of(event)));
    }
}
