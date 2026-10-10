package com.exchange.user.subaccount.exception;

/** Plain and code-less: each app maps it to its own error code. */
public class NotAMasterAccountException extends RuntimeException {

    public NotAMasterAccountException(String message) {
        super(message);
    }
}
