package com.exchange.spot.matching.trigger;

import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import com.exchange.spot.matching.book.Order;
import java.util.UUID;

/**
 * A conditional order waiting for its trigger. Deliberately not an {@link Order} — it is
 * not on the book, cannot be matched, and carries a stop price the book has no use for.
 *
 * <p>Keeping it a separate type is what stops {@code Order} growing a {@code stopPrice}
 * field that matching would have to ignore on every single order.
 */
public record PendingOrder(
        UUID id,
        long userId,
        Side side,
        OrderType type,
        TimeInForce timeInForce,
        long stopPrice,
        /** The limit price for the _LIMIT variants; zero for the plain stop types. */
        long limitPrice,
        /** A quote-asset budget instead of a base quantity when {@link #quoteQuantity}
         *  is true -- same reinterpreted-field convention as {@code Order}. Only ever
         *  true for the plain (market-firing) stop types; the _LIMIT variants fire at
         *  a fixed price, where a base quantity is unambiguous. */
        long quantity,
        /** Stamped by the API when the order was placed, and carried through the trigger. */
        int makerFeeBps,
        int takerFeeBps,
        String clientOrderId,
        SelfTradePreventionMode selfTradePreventionMode,
        /** Zero for a plain (non-trailing) conditional order. See
         *  {@code TrailingOrder} for what happens once one of these fires. */
        int trailingDeltaBps,
        boolean quoteQuantity) {

    public boolean isTrailing() {
        return trailingDeltaBps > 0;
    }

    public boolean isQuoteQuantity() {
        return quoteQuantity;
    }

    public TriggerDirection direction() {
        return TriggerDirection.of(type, side);
    }

    /**
     * What this becomes once it fires.
     *
     * <p>A plain STOP_LOSS or TAKE_PROFIT becomes a MARKET order — the point of a stop is
     * to get out, not to get a price. The _LIMIT variants become a LIMIT at their limit
     * price, which is the trade-off the user chose: a better price, or possibly no fill.
     */
    public Order toActiveOrder() {
        boolean isLimit = type == OrderType.ORDER_TYPE_STOP_LOSS_LIMIT
                || type == OrderType.ORDER_TYPE_TAKE_PROFIT_LIMIT;
        return isLimit
                ? new Order(id, userId, side, OrderType.ORDER_TYPE_LIMIT,
                            timeInForce, limitPrice, quantity, makerFeeBps, takerFeeBps,
                            selfTradePreventionMode)
                // A market order cannot rest, so its remainder is cancelled rather than
                // left behind at a price nobody asked for.
                : new Order(id, userId, side, OrderType.ORDER_TYPE_MARKET,
                            TimeInForce.TIME_IN_FORCE_IOC, 0, quantity,
                            makerFeeBps, takerFeeBps, selfTradePreventionMode, quoteQuantity);
    }
}
