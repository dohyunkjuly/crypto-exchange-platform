package com.exchange.copy.leader.exception;

/**
 * Every way leading can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class LeaderExceptions {

    private LeaderExceptions() {
    }

    /** The customer already has an application waiting, or already leads. */
    public static class AlreadyLeaderException extends RuntimeException {

        public AlreadyLeaderException(String message) {
            super(message);
        }
    }

    /** Another leader already goes by that name. */
    public static class DisplayNameTakenException extends RuntimeException {

        public DisplayNameTakenException(String message) {
            super(message);
        }
    }

    /** The lead account still has an order open or holds a coin worth selling, so its leader cannot stop yet. */
    public static class LeadAccountNotFlatException extends RuntimeException {

        public LeadAccountNotFlatException(String message) {
            super(message);
        }
    }

    /** The amount to lead with is under the minimum a lead account keeps, or over the most it may hold. */
    public static class LeadAmountOutOfRangeException extends RuntimeException {

        public LeadAmountOutOfRangeException(String message) {
            super(message);
        }
    }

    /** Taking that much out would leave less than a lead account must keep. */
    public static class LeadBalanceBelowMinimumException extends RuntimeException {

        public LeadBalanceBelowMinimumException(String message) {
            super(message);
        }
    }

    /** The leader is not taking followers: not active, or already at the limit. */
    public static class LeaderNotAcceptingException extends RuntimeException {

        public LeaderNotAcceptingException(String message) {
            super(message);
        }
    }

    /** No leader with that id. */
    public static class LeaderNotFoundException extends RuntimeException {

        public LeaderNotFoundException(String message) {
            super(message);
        }
    }

    /** The leader is not in a state this can be done from: approving one that is not waiting, suspending one that is not active. */
    public static class LeaderStatusException extends RuntimeException {

        public LeaderStatusException(String message) {
            super(message);
        }
    }

    /** The customer has no lead account: never applied, or not approved. */
    public static class NotALeaderException extends RuntimeException {

        public NotALeaderException(String message) {
            super(message);
        }
    }
}
