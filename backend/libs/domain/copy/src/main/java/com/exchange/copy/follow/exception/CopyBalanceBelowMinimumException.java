package com.exchange.copy.follow.exception;

/** Taking that much out would leave less than a copy account must hold. */
public class CopyBalanceBelowMinimumException extends RuntimeException {

    public CopyBalanceBelowMinimumException(String message) {
        super(message);
    }
}
