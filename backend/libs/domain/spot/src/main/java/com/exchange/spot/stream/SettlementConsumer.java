package com.exchange.spot.stream;

import com.exchange.marketdata.stream.WsMessage;
import com.exchange.wallet.asset.AssetNames;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.Settlement;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code exchange.spot.settlements.v1} and pushes each user their own fills and balances.
 *
 * <p>A second listener beside {@link UserWsConsumer}, not an extension of it. That one reads the
 * engine's output and knows when an order <em>changed state</em>; this one reads what settlement
 * persisted and knows what it <em>cost</em>. Two topics with different guarantees -- the event
 * log is retained forever, this one for an hour and may drop messages -- so sharing a listener
 * would couple their failure handling for no reason.
 *
 * <p><b>Every instance reads every message.</b> Same arrangement as the market socket and for
 * the same reason: a user's connection is on one instance while their account hashes to
 * whichever partition the key chose, so any instance may need to deliver any message.
 * {@link UserFanout#sendTo} returns on a single map probe when nobody here is that user,
 * which is the common case.
 *
 * <p><b>Never blocks, never retries.</b> Delivery happens on this listener thread, and replaying
 * a balance from a minute ago would put a stale number on a live screen. The record is
 * {@code GET /bff/wallet/balances} and {@code GET /bff/trades}; this is a freshness layer, which
 * is exactly why the topic it reads is allowed to lose messages at all.
 */
@Component
public class SettlementConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementConsumer.class);

    private final UserFanout sockets;
    private final AssetNames assetNames;

    public SettlementConsumer(UserFanout sockets, AssetNames assetNames) {
        this.sockets = sockets;
        this.assetNames = assetNames;
    }

    /**
     * Its own group, separate from {@link UserWsConsumer}'s, because it reads a different topic
     * -- and per instance, for the reason {@link UserWsConsumerGroup} spells out.
     *
     * <p>{@code latest} because nothing replays this. A reconnecting browser refetches its
     * balances and trades over REST; replaying an hour of somebody else's settled fills into
     * whoever happens to be connected at startup would be worse than useless.
     */
    @KafkaListener(topics = Topics.SPOT_SETTLEMENTS,
            groupId = "#{@settlementConsumerGroup.id}",
            properties = {"auto.offset.reset=latest"})
    public void onSettlement(ConsumerRecord<String, byte[]> record) {
        Settlement settlement;
        try {
            settlement = Settlement.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            log.error("skipping malformed settlement at partition {} offset {}",
                    record.partition(), record.offset(), e);
            return;
        }

        long userId = settlement.getUserId();
        switch (settlement.getPayloadCase()) {
            case BALANCE_CHANGED -> assetNames.of(settlement.getBalanceChanged().getAssetId())
                    .ifPresentOrElse(
                            asset -> sockets.sendTo(userId, WsMessage.push("balanceChanged",
                                    BalanceChangedData.of(settlement.getBalanceChanged(), asset))),
                            // An asset listed since the last cache refresh. Dropping the frame
                            // beats rendering a blank name: the balance is correct in MySQL and
                            // the next refresh fixes the stream.
                            () -> log.warn("unknown asset {} in a balance change for user {}",
                                    settlement.getBalanceChanged().getAssetId(), userId));
            case FILL_SETTLED -> sockets.sendTo(userId, WsMessage.push("fill",
                    FillData.of(settlement.getFillSettled(),
                            // A fee asset can legitimately be absent -- a zero fee writes no
                            // asset -- so this resolves to null rather than dropping the frame.
                            assetNames.of(settlement.getFillSettled().getFeeAssetId())
                                    .orElse(null))));
            default -> { }
        }
    }
}
