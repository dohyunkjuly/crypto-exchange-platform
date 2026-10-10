package com.exchange.copy.leader.exception;

/** The customer has no lead account: never applied, or not approved. */
public class NotALeaderException extends RuntimeException {

    public NotALeaderException(String message) {
        super(message);
    }
}
