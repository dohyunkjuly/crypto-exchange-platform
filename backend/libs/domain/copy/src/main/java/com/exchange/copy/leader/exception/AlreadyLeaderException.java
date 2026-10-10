package com.exchange.copy.leader.exception;

/** The customer already has an application waiting, or already leads. */
public class AlreadyLeaderException extends RuntimeException {

    public AlreadyLeaderException(String message) {
        super(message);
    }
}
