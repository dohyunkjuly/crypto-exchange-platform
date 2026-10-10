package com.exchange.spot.matching.trigger;

import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;

/**
 * Which way the price has to move for a conditional order to fire.
 *
 * <p>It is a function of BOTH the type and the side, and the combinations are easy to get
 * backwards, so they are written out rather than derived in the caller:
 *
 * <pre>
 *   STOP_LOSS   SELL   you hold it, sell if the price FALLS      → FALL
 *   STOP_LOSS   BUY    you are short, buy if the price RISES     → RISE
 *   TAKE_PROFIT SELL   sell into strength, if the price RISES    → RISE
 *   TAKE_PROFIT BUY    buy the dip, if the price FALLS           → FALL
 * </pre>
 *
 * <p>Getting one of these inverted means a stop-loss that fires on a rally — a bug that
 * only shows up with real money on it.
 */
public enum TriggerDirection {

    /** Fires when the last trade price is at or below the stop price. */
    FALL,

    /** Fires when the last trade price is at or above the stop price. */
    RISE;

    public static TriggerDirection of(OrderType type, Side side) {
        boolean stopLoss = type == OrderType.ORDER_TYPE_STOP_LOSS
                || type == OrderType.ORDER_TYPE_STOP_LOSS_LIMIT;
        if (stopLoss) {
            return side == Side.SIDE_SELL ? FALL : RISE;
        }
        return side == Side.SIDE_SELL ? RISE : FALL;
    }

    /** True when this price already satisfies the condition. */
    public boolean isMetBy(long price, long stopPrice) {
        return this == FALL ? price <= stopPrice : price >= stopPrice;
    }
}
