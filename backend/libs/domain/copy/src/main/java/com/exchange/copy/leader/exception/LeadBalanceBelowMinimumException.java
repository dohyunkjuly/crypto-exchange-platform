package com.exchange.copy.leader.exception;

/** Taking that much out would leave less than a lead account must keep. */
public class LeadBalanceBelowMinimumException extends RuntimeException {

    public LeadBalanceBelowMinimumException(String message) {
        super(message);
    }
}
