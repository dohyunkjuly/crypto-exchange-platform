package com.exchange.user.apikey.exception;

/**
 * Every way API keys can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class ApiKeyExceptions {

    private ApiKeyExceptions() {
    }

    /** No such key, or it belongs to a different user -- deliberately the same failure either
     *  way, so a caller can't use it to probe which key IDs exist. */
    public static class ApiKeyNotFoundException extends RuntimeException {

        public ApiKeyNotFoundException() {
            super("No such API key");
        }
    }

    /** Mirrors ck_user_api_keys_withdraw_needs_allowlist -- checked here first so a bad
     *  request gets a clean, named failure instead of a raw SQL constraint violation. No
     *  ErrorCode attached: this module can't depend on either app, so each app's own
     *  exception handling maps this to its own ErrorCode. */
    public static class WithdrawalRequiresAllowlistException extends RuntimeException {

        public WithdrawalRequiresAllowlistException() {
            super("A withdrawal-capable key needs an IP allowlist");
        }
    }
}
