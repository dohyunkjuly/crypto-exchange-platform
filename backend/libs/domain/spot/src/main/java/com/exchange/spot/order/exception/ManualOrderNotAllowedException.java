package com.exchange.spot.order.exception;

/** The account is traded for its owner, not by them: a copy account (docs/reference/copy-trading.md). */
public class ManualOrderNotAllowedException extends RuntimeException {

    public ManualOrderNotAllowedException(String message) {
        super(message);
    }
}
