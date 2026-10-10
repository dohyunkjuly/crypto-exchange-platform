package com.exchange.copy.leader.exception;

/** Another leader already goes by that name. */
public class DisplayNameTakenException extends RuntimeException {

    public DisplayNameTakenException(String message) {
        super(message);
    }
}
