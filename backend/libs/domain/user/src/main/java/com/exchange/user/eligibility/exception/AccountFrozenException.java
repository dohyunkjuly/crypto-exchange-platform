package com.exchange.user.eligibility.exception;

/** The account may not trade: a frozen sub-account, a customer an admin froze, or one of the
 *  exchange's own accounts, which never trade at all (docs/reference/treasury.md). */
public class AccountFrozenException extends RuntimeException {

    public AccountFrozenException() {
        super("account is frozen");
    }
}
