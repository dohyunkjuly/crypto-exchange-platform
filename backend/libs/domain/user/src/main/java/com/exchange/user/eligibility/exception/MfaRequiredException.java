package com.exchange.user.eligibility.exception;

/**
 * The account has no active second factor, and this operation will not proceed without one.
 *
 * <p>Distinct from {@code MfaNotEnabledException} in the mfa package, which means "there is
 * nothing here to challenge you against". This one means "you are eligible in every other
 * respect, go and enrol" -- and an app maps it to a different message and a different place
 * to send the user.
 */
public class MfaRequiredException extends RuntimeException {

    public MfaRequiredException() {
        super("Two-factor authentication must be enabled for this action");
    }
}
