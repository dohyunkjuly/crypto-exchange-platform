package com.exchange.spot.order.exception;

/** The account-wide or per-symbol open-CONDITIONAL-order cap
 *  (SpotExchangeOrderLimits.maxNumAlgoOrders / SpotSymbol.maxOpenConditionalOrders, or
 *  a user's override of either) is already at or over its limit -- a separate, tighter
 *  cap than {@link OrderLimitExceededException}'s. */
public class AlgoOrderLimitExceededException extends RuntimeException {

    public AlgoOrderLimitExceededException() {
        super("Too many open conditional orders");
    }
}
