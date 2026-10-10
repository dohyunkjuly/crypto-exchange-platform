package com.exchange.spot.order.exception;

/** GTD, or a missing time in force on anything but a MARKET order, which needs none. */
public class TimeInForceNotSupportedException extends RuntimeException {

    public TimeInForceNotSupportedException(String reason) {
        super(reason);
    }
}
