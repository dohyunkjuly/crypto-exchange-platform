package com.exchange.user.kyc;

/**
 * Published when a user submits their details for verification.
 *
 * <p>Exists so something can react to a submission without {@code KycService} knowing what
 * -- in development that is an auto-approver, and later it is the call that creates an
 * applicant at the verification provider. A provider's webhook does not use this: it
 * arrives from outside and calls {@code approve} directly.
 */
public record KycSubmittedEvent(Long userId) {
}
