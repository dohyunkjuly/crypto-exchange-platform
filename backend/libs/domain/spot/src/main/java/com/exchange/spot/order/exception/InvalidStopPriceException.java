package com.exchange.spot.order.exception;

/** Missing on a conditional order that isn't trailing (it's the only field that can
 *  stand in for a required stop price), or not on the symbol's tick size. */
public class InvalidStopPriceException extends RuntimeException {

    public InvalidStopPriceException(String reason) {
        super(reason);
    }
}
