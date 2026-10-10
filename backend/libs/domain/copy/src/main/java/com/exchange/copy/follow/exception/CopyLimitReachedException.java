package com.exchange.copy.follow.exception;

/** The customer already has as many copy accounts as one may have. */
public class CopyLimitReachedException extends RuntimeException {

    public CopyLimitReachedException(String message) {
        super(message);
    }
}
