package com.exchange.spot.order.exception;

/** Only an order still waiting can change: resting GTC on the book, or parked on its trigger.
 *  A terminal order, a market order and an IOC or FOK one have nothing left to amend. */
public class OrderNotAmendableException extends RuntimeException {

    public OrderNotAmendableException(String reason) {
        super(reason);
    }
}
