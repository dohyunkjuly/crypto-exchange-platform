package com.exchange.user.mfa.exception;

/**
 * The code was wrong, expired, or already spent.
 *
 * <p>One exception for all three on purpose. Telling a caller which of those it was tells
 * an attacker whether they have the right secret and merely mistimed it, whether the code
 * was already used, or whether they are nowhere near -- and the difference between those
 * answers is exactly what a guessing attack needs.
 */
public class InvalidMfaCodeException extends RuntimeException {

    public InvalidMfaCodeException() {
        super("Invalid or expired verification code");
    }
}
