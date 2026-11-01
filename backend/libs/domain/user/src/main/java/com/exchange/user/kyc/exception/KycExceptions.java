package com.exchange.user.kyc.exception;

/**
 * Every way identity verification can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class KycExceptions {

    private KycExceptions() {
    }

    /**
     * Submitting over a profile that is already approved and current.
     *
     * <p>Refused rather than accepted, because accepting would drop a verified account back
     * to PENDING and revoke everything it had unlocked -- someone re-checking their details
     * out of curiosity would find they could no longer trade. Upgrading to a higher tier is a
     * different operation and will need its own path.
     */
    public static class KycAlreadyVerifiedException extends RuntimeException {

        public KycAlreadyVerifiedException() {
            super("This account is already verified");
        }
    }
}
