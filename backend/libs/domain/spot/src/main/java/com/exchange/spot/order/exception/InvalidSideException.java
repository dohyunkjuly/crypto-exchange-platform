package com.exchange.spot.order.exception;

/** {@code side} is not one of {@code BUY} or {@code SELL}. */
public class InvalidSideException extends RuntimeException {

    public InvalidSideException(String value) {
        super("side must be BUY or SELL, got '" + value + "'");
    }
}
