package com.exchange.user.mfa.exception;

/** The account has no active second factor -- either it never enrolled one, or the
 *  operation being attempted requires one and this account cannot satisfy it yet. */
public class MfaNotEnabledException extends RuntimeException {

    public MfaNotEnabledException() {
        super("Two-factor authentication is not enabled on this account");
    }
}
