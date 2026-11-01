package com.exchange.copy.follow.exception;

/**
 * Every way following a leader can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class FollowExceptions {

    private FollowExceptions() {
    }

    /** The customer already has an open copy account for this leader. */
    public static class AlreadyFollowingException extends RuntimeException {

        public AlreadyFollowingException(String message) {
            super(message);
        }
    }

    /** Less than the mode asks for, or an amount per order the funding cannot cover. */
    public static class CopyAmountTooSmallException extends RuntimeException {

        public CopyAmountTooSmallException(String message) {
            super(message);
        }
    }

    /** Taking that much out would leave less than a copy account must hold. */
    public static class CopyBalanceBelowMinimumException extends RuntimeException {

        public CopyBalanceBelowMinimumException(String message) {
            super(message);
        }
    }

    /** The customer already has as many copy accounts as one may have. */
    public static class CopyLimitReachedException extends RuntimeException {

        public CopyLimitReachedException(String message) {
            super(message);
        }
    }

    /** The copy account is closing or closed: nothing more can be done to it. */
    public static class FollowNotActiveException extends RuntimeException {

        public FollowNotActiveException(String message) {
            super(message);
        }
    }

    /** No such copy account, or it is somebody else's. */
    public static class FollowNotFoundException extends RuntimeException {

        public FollowNotFoundException(String message) {
            super(message);
        }
    }

    /** A leader cannot copy their own lead account. */
    public static class SelfFollowException extends RuntimeException {

        public SelfFollowException(String message) {
            super(message);
        }
    }
}
