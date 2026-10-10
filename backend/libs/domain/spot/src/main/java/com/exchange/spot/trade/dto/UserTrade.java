package com.exchange.spot.trade.dto;

import com.exchange.spot.order.OrderType;
import java.time.Instant;
import java.util.UUID;

/**
 * One of a user's own fills, seen entirely from their side.
 *
 * <p>A {@code SpotTrade} row is one fact about two people: it holds both user ids, both
 * order ids and both fees. This is that row with one side chosen and the other discarded --
 * {@code side} is what <em>this</em> user did, {@code fee} is what <em>this</em> user paid,
 * and the counterparty is not represented at all.
 *
 * <p><b>Picking the side is done once, here, in the domain.</b> It is four lines of
 * arithmetic and easy to get backwards: a buyer who was the maker is the seller's taker, and
 * confusing them shows a user the other party's fee. Leaving each app to work it out would
 * mean two implementations of a rule with that failure mode, and the type that survived the
 * mistake would still compile.
 *
 * <p>{@code isMaker} matters to a user beyond curiosity: maker and taker rates differ, and a
 * negative fee is a rebate rather than an error.
 *
 * <p>{@code orderType} is the type of this user's order, so a stop that fired reads as a stop
 * rather than as the market order it became. Null only if that order row is gone.
 *
 * <p>Prices, quantities and fees are 1e8-scaled longs.
 */
public record UserTrade(UUID tradeId,
                        String symbol,
                        UUID orderId,
                        OrderType orderType,
                        long price,
                        long quantity,
                        long quoteQuantity,
                        Side side,
                        boolean isMaker,
                        long fee,
                        String feeAsset,
                        Instant executedAt) {

    /** What this user did, not what the taker did. */
    public enum Side {
        BUY, SELL
    }
}
