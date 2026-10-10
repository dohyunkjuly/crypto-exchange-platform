package com.exchange.copy.leader.exception;

/** The leader is not in a state this can be done from: approving one that is not waiting, suspending one that is not active. */
public class LeaderStatusException extends RuntimeException {

    public LeaderStatusException(String message) {
        super(message);
    }
}
