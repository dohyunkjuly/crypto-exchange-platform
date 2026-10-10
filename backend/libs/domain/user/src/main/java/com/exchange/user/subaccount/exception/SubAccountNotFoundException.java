package com.exchange.user.subaccount.exception;

/** Plain and code-less: each app maps it to its own error code. */
public class SubAccountNotFoundException extends RuntimeException {

    public SubAccountNotFoundException(String message) {
        super(message);
    }
}
