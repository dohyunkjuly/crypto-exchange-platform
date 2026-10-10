package com.exchange.spot.order.exception;

/** Missing where required, off the symbol's step size, or outside its bounds --
 *  covers both a base {@code quantity} and a {@code quoteOrderQty}. */
public class InvalidQuantityException extends RuntimeException {

    public InvalidQuantityException(String reason) {
        super(reason);
    }
}
