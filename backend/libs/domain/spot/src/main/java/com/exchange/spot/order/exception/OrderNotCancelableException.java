package com.exchange.spot.order.exception;

import com.exchange.spot.order.OrderStatus;

/**
 * The order has already reached a terminal state, so there is nothing left to cancel.
 *
 * <p>Note this is only the check the API can make from its own copy of the order. It is
 * not a guarantee: an order that looks open here may fill in the engine a millisecond
 * later, before the cancel command arrives. That race is resolved by the engine, which
 * simply ignores a cancel for an order it no longer holds -- see {@code
 * MatchingEngine.cancelOrder}. This exception exists to reject the obviously-pointless
 * case early, not to make cancellation synchronous.
 */
public class OrderNotCancelableException extends RuntimeException {

    public OrderNotCancelableException(OrderStatus status) {
        super("Order is already " + status + " and cannot be canceled");
    }
}
