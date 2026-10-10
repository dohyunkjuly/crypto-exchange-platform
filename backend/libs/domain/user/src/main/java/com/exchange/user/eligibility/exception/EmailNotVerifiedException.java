package com.exchange.user.eligibility.exception;

/**
 * The account's email address has never been confirmed.
 *
 * <p>Checked before KYC and before 2FA, because it is the first step of signup and the
 * cheapest to fix -- telling someone to upload a passport when the real problem is an
 * unclicked link in their inbox sends them a long way in the wrong direction.
 *
 * <p>It also matters on its own: an unverified address is one we cannot reach. Everything
 * that follows -- recovery, security alerts, a withdrawal confirmation -- assumes the
 * address works, and an account that can trade but cannot be contacted has no way back
 * from anything going wrong.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException() {
        super("Confirm your email address before continuing");
    }
}
