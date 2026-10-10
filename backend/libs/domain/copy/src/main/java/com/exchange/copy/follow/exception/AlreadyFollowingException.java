package com.exchange.copy.follow.exception;

/** The customer already has an open copy account for this leader. */
public class AlreadyFollowingException extends RuntimeException {

    public AlreadyFollowingException(String message) {
        super(message);
    }
}
