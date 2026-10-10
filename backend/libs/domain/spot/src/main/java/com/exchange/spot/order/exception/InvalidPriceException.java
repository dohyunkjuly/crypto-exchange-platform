package com.exchange.spot.order.exception;

/** Missing where required, or not on the symbol's tick size. */
public class InvalidPriceException extends RuntimeException {

    public InvalidPriceException(String reason) {
        super(reason);
    }
}
