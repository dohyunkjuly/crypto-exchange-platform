package com.exchange.user.subaccount.exception;

/**
 * Every way sub-accounts can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class SubAccountExceptions {

    private SubAccountExceptions() {
    }

    /** Plain and code-less: each app maps it to its own error code. */
    public static class NotAMasterAccountException extends RuntimeException {

        public NotAMasterAccountException(String message) {
            super(message);
        }
    }

    /** Plain and code-less: each app maps it to its own error code. */
    public static class SubAccountFrozenException extends RuntimeException {

        public SubAccountFrozenException(String message) {
            super(message);
        }
    }

    /** Plain and code-less: each app maps it to its own error code. */
    public static class SubAccountLimitReachedException extends RuntimeException {

        public SubAccountLimitReachedException(String message) {
            super(message);
        }
    }

    /** Plain and code-less: each app maps it to its own error code. */
    public static class SubAccountNotFoundException extends RuntimeException {

        public SubAccountNotFoundException(String message) {
            super(message);
        }
    }
}
