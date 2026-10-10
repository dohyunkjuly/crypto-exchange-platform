package com.exchange.user.apikey.exception;

/** Mirrors ck_user_api_keys_withdraw_needs_allowlist -- checked here first so a bad
 *  request gets a clean, named failure instead of a raw SQL constraint violation. No
 *  ErrorCode attached: this module can't depend on either app, so each app's own
 *  exception handling maps this to its own ErrorCode. */
public class WithdrawalRequiresAllowlistException extends RuntimeException {

    public WithdrawalRequiresAllowlistException() {
        super("A withdrawal-capable key needs an IP allowlist");
    }
}
