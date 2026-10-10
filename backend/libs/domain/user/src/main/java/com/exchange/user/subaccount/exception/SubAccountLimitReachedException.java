package com.exchange.user.subaccount.exception;

/** Plain and code-less: each app maps it to its own error code. */
public class SubAccountLimitReachedException extends RuntimeException {

    public SubAccountLimitReachedException(String message) {
        super(message);
    }
}
