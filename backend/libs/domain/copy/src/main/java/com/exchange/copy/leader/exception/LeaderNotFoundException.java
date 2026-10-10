package com.exchange.copy.leader.exception;

/** No leader with that id. */
public class LeaderNotFoundException extends RuntimeException {

    public LeaderNotFoundException(String message) {
        super(message);
    }
}
