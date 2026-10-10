package com.exchange.user.eligibility.exception;

import com.exchange.user.kyc.KycLevel;
import lombok.Getter;

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
public class KycRequiredException extends RuntimeException {

    private final KycLevel requiredLevel;

    public KycRequiredException(KycLevel requiredLevel) {
        super("Identity verification level " + requiredLevel + " is required");
        this.requiredLevel = requiredLevel;
    }
}
