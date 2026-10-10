package com.exchange.spot.order.exception;

/** {@code timeInForce} is not one of {@link com.exchange.spot.order.TimeInForce}'s names. A
 *  known but unsupported one is {@link TimeInForceNotSupportedException} instead. */
public class InvalidTimeInForceException extends RuntimeException {

    public InvalidTimeInForceException(String value) {
        super("timeInForce must be one of GTC, IOC or FOK, got '" + value + "'");
    }
}
