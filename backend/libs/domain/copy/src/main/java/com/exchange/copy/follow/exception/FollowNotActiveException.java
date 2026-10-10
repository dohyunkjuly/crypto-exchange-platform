package com.exchange.copy.follow.exception;

/** The copy account is closing or closed: nothing more can be done to it. */
public class FollowNotActiveException extends RuntimeException {

    public FollowNotActiveException(String message) {
        super(message);
    }
}
