package com.exchange.spot.order.exception;

/** No field was sent, or every one sent already has that value. */
public class NothingToAmendException extends RuntimeException {

    public NothingToAmendException() {
        super("the amend changes nothing");
    }
}
