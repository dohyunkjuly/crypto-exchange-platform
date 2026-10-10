package com.exchange.spot.order.exception;

/** {@code price * quantity} is outside the symbol's allowed notional range. */
public class NotionalOutOfRangeException extends RuntimeException {

    public NotionalOutOfRangeException() {
        super("Order notional is out of the symbol's allowed range");
    }
}
