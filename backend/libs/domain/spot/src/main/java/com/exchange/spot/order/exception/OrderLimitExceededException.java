package com.exchange.spot.order.exception;

/** The account-wide or per-symbol open-order cap (SpotExchangeOrderLimits.maxNumOrders
 *  / SpotSymbol.maxOpenOrders, or a user's override of either) is already at or over
 *  its limit. See {@link AlgoOrderLimitExceededException} for the narrower,
 *  conditional-orders-only cap. */
public class OrderLimitExceededException extends RuntimeException {

    public OrderLimitExceededException() {
        super("Too many open orders");
    }
}
