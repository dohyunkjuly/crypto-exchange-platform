package com.exchange.spot.order.exception;

/** FOK with EXPIRE_BOTH. FOK must leave the book untouched when it cannot fill, and
 *  EXPIRE_BOTH asks to cancel the resting order, so the two contradict each other. */
public class FokWithExpireBothNotSupportedException extends RuntimeException {

    public FokWithExpireBothNotSupportedException() {
        super("EXPIRE_BOTH cannot be combined with FOK");
    }
}
