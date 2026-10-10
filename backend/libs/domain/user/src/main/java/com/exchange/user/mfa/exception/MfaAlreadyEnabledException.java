package com.exchange.user.mfa.exception;

/** Re-enrolling over a working factor would let anyone holding a live session silently
 *  swap the second factor for their own. Disable the existing one first, which itself
 *  requires passing it. */
public class MfaAlreadyEnabledException extends RuntimeException {

    public MfaAlreadyEnabledException() {
        super("Two-factor authentication is already enabled");
    }
}
