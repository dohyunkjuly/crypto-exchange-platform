package com.exchange.copy.follow.exception;

/** A leader cannot copy their own lead account. */
public class SelfFollowException extends RuntimeException {

    public SelfFollowException(String message) {
        super(message);
    }
}
