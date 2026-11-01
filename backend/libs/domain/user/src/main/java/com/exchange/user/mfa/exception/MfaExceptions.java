package com.exchange.user.mfa.exception;

/**
 * Every way the second factor can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class MfaExceptions {

    private MfaExceptions() {
    }

    /**
     * The code was wrong, expired, or already spent.
     *
     * <p>One exception for all three on purpose. Telling a caller which of those it was tells
     * an attacker whether they have the right secret and merely mistimed it, whether the code
     * was already used, or whether they are nowhere near -- and the difference between those
     * answers is exactly what a guessing attack needs.
     */
    public static class InvalidMfaCodeException extends RuntimeException {

        public InvalidMfaCodeException() {
            super("Invalid or expired verification code");
        }
    }

    /** Re-enrolling over a working factor would let anyone holding a live session silently
     *  swap the second factor for their own. Disable the existing one first, which itself
     *  requires passing it. */
    public static class MfaAlreadyEnabledException extends RuntimeException {

        public MfaAlreadyEnabledException() {
            super("Two-factor authentication is already enabled");
        }
    }

    /** Confirming an enrolment that was never begun, or one that a later attempt superseded. */
    public static class MfaEnrolmentNotStartedException extends RuntimeException {

        public MfaEnrolmentNotStartedException() {
            super("No two-factor enrolment is in progress");
        }
    }

    /** The account has no active second factor -- either it never enrolled one, or the
     *  operation being attempted requires one and this account cannot satisfy it yet. */
    public static class MfaNotEnabledException extends RuntimeException {

        public MfaNotEnabledException() {
            super("Two-factor authentication is not enabled on this account");
        }
    }
}
