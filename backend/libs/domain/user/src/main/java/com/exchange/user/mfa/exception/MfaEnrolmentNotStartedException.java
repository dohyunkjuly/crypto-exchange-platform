package com.exchange.user.mfa.exception;

/** Confirming an enrolment that was never begun, or one that a later attempt superseded. */
public class MfaEnrolmentNotStartedException extends RuntimeException {

    public MfaEnrolmentNotStartedException() {
        super("No two-factor enrolment is in progress");
    }
}
