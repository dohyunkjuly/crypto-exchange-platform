package com.exchange.copy.follow.exception;

/** No such copy account, or it is somebody else's. */
public class FollowNotFoundException extends RuntimeException {

    public FollowNotFoundException(String message) {
        super(message);
    }
}
