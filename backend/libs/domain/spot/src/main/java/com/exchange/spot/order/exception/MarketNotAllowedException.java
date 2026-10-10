package com.exchange.spot.order.exception;

/** The market is open, but not to this account: a lead account outside the copyable markets. */
public class MarketNotAllowedException extends RuntimeException {

    public MarketNotAllowedException(String message) {
        super(message);
    }
}
