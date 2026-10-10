package com.exchange.spot.matching.book;

import com.exchange.contracts.spot.v1.RejectReason;
import java.util.List;

/**
 * Everything one submitted order caused. The engine turns this into events; the book
 * itself publishes nothing.
 *
 * @param fills            crossings, in the order they happened
 * @param restingQuantity  what was left on the book. Zero for a fully filled order, for a
 *                         market order, for an IOC remainder, and for a rejection.
 * @param canceledQuantity what was thrown away rather than rested — an IOC or market
 *                         remainder. Never both this and {@code restingQuantity}.
 * @param rejection        set only when nothing happened at all; fills is empty
 * @param expiredMakers    resting orders cancelled by self-trade prevention under
 *                         EXPIRE_MAKER or EXPIRE_BOTH.
 *                         <p>The whole {@link Order}, not just its id: emitting an
 *                         OrderCanceled event needs the user, price, side and remaining
 *                         quantity, and by the time the engine sees this list the order
 *                         has already been removed from the book, so there is nothing
 *                         left to look it up in.
 * @param takerExpiredBySelfTrade true when {@code canceledQuantity} exists because self-trade
 *                         prevention (EXPIRE_TAKER or EXPIRE_BOTH) cut the taker short,
 *                         rather than because an IOC/market remainder had nowhere to rest.
 *                         Tells the engine which {@code CancelReason} to emit.
 */
public record MatchResult(
        List<Fill> fills,
        long restingQuantity,
        long canceledQuantity,
        RejectReason rejection,
        List<Order> expiredMakers,
        boolean takerExpiredBySelfTrade) {

    public static MatchResult rejected(RejectReason reason) {
        return new MatchResult(List.of(), 0, 0, reason, List.of(), false);
    }

    public boolean isRejected() {
        return rejection != null;
    }

    public long filledQuantity() {
        return fills.stream().mapToLong(Fill::quantity).sum();
    }

    /** Sum of price*quantity over the fills — the denominator of an average fill price. */
    public long filledQuoteQuantity() {
        return fills.stream().mapToLong(Fill::quoteQuantity).sum();
    }

    public boolean isRested() {
        return restingQuantity > 0;
    }
}
