package com.exchange.copy.leader.exception;

/** The leader is not taking followers: not active, or already at the limit. */
public class LeaderNotAcceptingException extends RuntimeException {

    public LeaderNotAcceptingException(String message) {
        super(message);
    }
}
