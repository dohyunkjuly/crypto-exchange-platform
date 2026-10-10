package com.exchange.user.eligibility.exception;

/** An admin has suspended trading on the account, or on its master: no new order, amend,
 *  trading key or sub-account transfer. Logging in, reading and cancelling still work.
 *  Each app answers it with a neutral message: the customer is never told they were suspended. */
public class TradingSuspendedException extends RuntimeException {

    public TradingSuspendedException() {
        super("trading is not available on this account");
    }
}
