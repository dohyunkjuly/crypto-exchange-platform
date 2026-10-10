package com.exchange.copy.leader.exception;

/** The lead account still has an order open or holds a coin worth selling, so its leader cannot stop yet. */
public class LeadAccountNotFlatException extends RuntimeException {

    public LeadAccountNotFlatException(String message) {
        super(message);
    }
}
