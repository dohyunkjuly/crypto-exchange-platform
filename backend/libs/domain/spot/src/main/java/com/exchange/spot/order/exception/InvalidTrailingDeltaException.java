package com.exchange.spot.order.exception;

/** {@code trailingDeltaBps} outside [10, 2000] (same bounds the matching engine
 *  enforces), or set on a non-conditional order. */
public class InvalidTrailingDeltaException extends RuntimeException {

    public InvalidTrailingDeltaException() {
        super("trailingDeltaBps must be between 10 and 2000, and only on a conditional order");
    }
}
