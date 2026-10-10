package com.exchange.user.kyc.exception;

/**
 * Submitting over a profile that is already approved and current.
 *
 * <p>Refused rather than accepted, because accepting would drop a verified account back
 * to PENDING and revoke everything it had unlocked -- someone re-checking their details
 * out of curiosity would find they could no longer trade. Upgrading to a higher tier is a
 * different operation and will need its own path.
 */
public class KycAlreadyVerifiedException extends RuntimeException {

    public KycAlreadyVerifiedException() {
        super("This account is already verified");
    }
}
