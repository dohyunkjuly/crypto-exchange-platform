package com.exchange.spot.matching.book;

import com.exchange.common.money.Money;
import java.util.UUID;

/**
 * One taker order crossing with one resting maker order.
 *
 * <p>{@code price} is always the MAKER's price, never the taker's. A buyer willing to pay
 * 63300 who crosses a resting sell at 63250 pays 63250 — the price improvement belongs to
 * the taker because the maker was there first. Getting this backwards is the single most
 * expensive bug an order book can have, so it is stated here rather than left implicit.
 */
public record Fill(
        UUID makerOrderId,
        long makerUserId,
        long price,
        long quantity,
        long makerRemaining,
        /**
         * The MAKER's own maker rate, stamped when their order was placed. Carried here
         * because a fully filled maker leaves the book during matching, so the engine
         * cannot look it up afterwards.
         */
        int makerFeeBps) {

    /** True when this fill consumed the maker entirely and it has left the book. */
    public boolean makerFullyFilled() {
        return makerRemaining == 0;
    }

    /** price * quantity, unscaled once so the result stays 1e8-scaled. */
    public long quoteQuantity() {
        return Money.multiply(price, quantity);
    }
}
