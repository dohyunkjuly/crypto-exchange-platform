package com.exchange.user.subaccount.exception;

/** Plain and code-less: each app maps it to its own error code. */
public class SubAccountFrozenException extends RuntimeException {

    public SubAccountFrozenException(String message) {
        super(message);
    }
}
