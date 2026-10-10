package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The six-digit code from the email.
 *
 * <p>No length or pattern constraint, deliberately. Rejecting a malformed code before
 * checking it would answer faster for the wrong shape than for the wrong value, and the
 * service already treats every failure identically. It also means a user pasting a code
 * with a stray space gets the same honest "that is not right" rather than a validation
 * error about formatting.
 */
public record VerifyEmailRequest(@NotBlank String code) {
}
