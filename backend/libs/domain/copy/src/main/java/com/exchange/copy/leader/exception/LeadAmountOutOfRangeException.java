package com.exchange.copy.leader.exception;

/** The amount to lead with is under the minimum a lead account keeps, or over the most it may hold. */
public class LeadAmountOutOfRangeException extends RuntimeException {

    public LeadAmountOutOfRangeException(String message) {
        super(message);
    }
}
