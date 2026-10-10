package com.exchange.spot.settlement.trade;

import com.exchange.common.id.Ulid;
import com.exchange.common.kafka.Topics;
import com.exchange.contracts.spot.v1.FillSettled;
import com.exchange.contracts.spot.v1.Settlement;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.spot.trade.SpotTrade;
import com.google.protobuf.ByteString;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Announces a settled fill on {@code exchange.spot.settlements.v1}, after it commits.
 *
 * <p><b>Why this lives in settlement rather than in a domain service,</b> when its sibling
 * {@code BalanceChangePublisher} lives in {@code BalanceService}. Balances have four writers --
 * settlement, web-bff, spot-open-api, and whatever credits a deposit later -- so the only
 * place that cannot be forgotten is the write itself. A trade has exactly one writer, by
 * design: this app is "the one and only writer of wallet/order state". There is no second
 * caller to forget, so a domain service hosting the producer would be indirection for one
 * client.
 *
 * <p>And settlement holds something no domain service could: the fee <b>amount</b>. The
 * engine publishes fee rates and says so outright -- "settlement does that arithmetic" -- so
 * the number a user actually paid does not exist until this app computes it. That is the whole
 * reason this topic carries fills at all rather than letting web-bff derive them from
 * {@code TradeExecuted}.
 *
 * <p><b>Two messages per fill, one per participant</b>, each built from that side's own
 * fields. The counterparty appears in neither -- not their id, not their order, not their fee.
 * Reusing one message for both sides is the mistake that would hand a user somebody else's
 * position, and it would look like nothing from the inside.
 *
 * <p>After commit, off the calling thread, and a failure is logged and dropped -- for the
 * reasons {@code BalanceChangePublisher} sets out in full.
 */
@Component
public class FillSettledPublisher {

    private static final Logger log = LoggerFactory.getLogger(FillSettledPublisher.class);

    private static final int QUEUE_CAPACITY = 10_000;

    private final KafkaTemplate<String, byte[]> kafka;

    /**
     * One thread, and a bounded queue that discards the <b>oldest</b> when full.
     *
     * <p>Note the difference from the balance publisher, where discarding the oldest is
     * strictly right because each message carries an absolute balance and the newest is the
     * truest. A fill is not absolute -- every one is a distinct event -- so dropping any of
     * them loses information rather than superseding it. That is acceptable only because the
     * fills themselves are in {@code spot_trades} and served by {@code GET /bff/trades}; this
     * is a notification, and the oldest is the one a client is most likely to have already
     * refetched.
     */
    private final ThreadPoolExecutor publisher = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "fill-settled-publisher");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    public FillSettledPublisher(KafkaTemplate<String, byte[]> kafka) {
        this.kafka = kafka;
    }

    /**
     * Registers these trades to be announced once the surrounding transaction commits.
     *
     * <p>Takes only the rows actually written. A redelivered batch re-derives the same
     * mutations but inserts nothing, and announcing then would tell a client a fill happened
     * twice.
     */
    void publishAfterCommit(String symbol, List<SpotTrade> newTrades) {
        if (newTrades.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("no active transaction; not announcing {} settled fills", newTrades.size());
            return;
        }
        List<SpotTrade> trades = List.copyOf(newTrades);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                trades.forEach(trade -> submit(symbol, trade));
            }
        });
    }

    /** Both sides, built independently. */
    private void submit(String symbol, SpotTrade trade) {
        // The buyer is the maker exactly when buyerIsMaker; the seller is the maker exactly
        // when they are not. Read straight across and a taker is reported as a maker, which
        // also mislabels the sign of their fee.
        publish(symbol, trade, trade.getBuyerUserId(), trade.getBuyerOrderId(), Side.SIDE_BUY,
                trade.isBuyerIsMaker(), trade.getBuyerFee(), trade.getBuyerFeeAssetId());
        publish(symbol, trade, trade.getSellerUserId(), trade.getSellerOrderId(), Side.SIDE_SELL,
                !trade.isBuyerIsMaker(), trade.getSellerFee(), trade.getSellerFeeAssetId());
    }

    private void publish(String symbol, SpotTrade trade, Long userId, java.util.UUID orderId,
                         Side side, boolean isMaker, long fee, Long feeAssetId) {
        publisher.execute(() -> {
            try {
                FillSettled.Builder fill = FillSettled.newBuilder()
                        .setTradeId(bytes(trade.getId()))
                        .setOrderId(bytes(orderId))
                        .setSymbol(symbol)
                        .setPrice(trade.getPrice())
                        .setQuantity(trade.getQuantity())
                        .setQuoteQuantity(trade.getQuoteQuantity())
                        .setSide(side)
                        .setIsMaker(isMaker)
                        // Read back from the row, not recomputed. Negative is a rebate, which
                        // the row has only carried since ck_spot_trades_fees allowed it relaxed a check that forbade one.
                        .setFee(fee);
                if (feeAssetId != null) {
                    fill.setFeeAssetId(feeAssetId);
                }
                Settlement message = Settlement.newBuilder()
                        .setUserId(userId)
                        .setTimestampUs(Instant.now().toEpochMilli() * 1_000)
                        .setFillSettled(fill)
                        .build();
                kafka.send(Topics.SPOT_SETTLEMENTS, Long.toString(userId), message.toByteArray());
            } catch (RuntimeException e) {
                log.warn("could not announce a settled fill for user {} trade {}; the trade "
                        + "itself is committed and readable over REST", userId, trade.getId(), e);
            }
        });
    }

    private static ByteString bytes(java.util.UUID id) {
        return ByteString.copyFrom(Ulid.toBytes(id));
    }

    @PreDestroy
    void shutdown() {
        publisher.shutdownNow();
    }
}
