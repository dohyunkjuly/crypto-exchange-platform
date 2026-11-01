package com.exchange.user.eligibility.exception;

import com.exchange.user.kyc.KycLevel;
import lombok.Getter;

/**
 * Every way the trading gate can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class EligibilityExceptions {

    private EligibilityExceptions() {
    }

    /** The account may not trade: a frozen sub-account, a customer an admin froze, or one of the
     *  exchange's own accounts, which never trade at all (docs/reference/treasury.md). */
    public static class AccountFrozenException extends RuntimeException {

        public AccountFrozenException() {
            super("account is frozen");
        }
    }

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
    public static class EmailNotVerifiedException extends RuntimeException {

        public EmailNotVerifiedException() {
            super("Confirm your email address before continuing");
        }
    }

    /**
     * The account is not verified far enough for what it just tried to do.
     *
     * <p>Carries the level required, so the app rendering this can send the user to the right
     * step instead of a generic "verify your account" -- the difference between "upload an ID"
     * and "upload a proof of address" is the difference between a user finishing and giving up.
     *
     * <p>Deliberately does not carry the level they currently hold. That is the caller's own
     * data to fetch if it wants to show it, and putting it in an exception message risks it
     * being logged somewhere it does not belong.
     */
    @Getter
    public static class KycRequiredException extends RuntimeException {

        private final KycLevel requiredLevel;

        public KycRequiredException(KycLevel requiredLevel) {
            super("Identity verification level " + requiredLevel + " is required");
            this.requiredLevel = requiredLevel;
        }
    }

    /**
     * The account has no active second factor, and this operation will not proceed without one.
     *
     * <p>Distinct from {@code MfaNotEnabledException} in the mfa package, which means "there is
     * nothing here to challenge you against". This one means "you are eligible in every other
     * respect, go and enrol" -- and an app maps it to a different message and a different place
     * to send the user.
     */
    public static class MfaRequiredException extends RuntimeException {

        public MfaRequiredException() {
            super("Two-factor authentication must be enabled for this action");
        }
    }

    /** An admin has suspended trading on the account, or on its master: no new order, amend,
     *  trading key or sub-account transfer. Logging in, reading and cancelling still work.
     *  Each app answers it with a neutral message: the customer is never told they were suspended. */
    public static class TradingSuspendedException extends RuntimeException {

        public TradingSuspendedException() {
            super("trading is not available on this account");
        }
    }
}
