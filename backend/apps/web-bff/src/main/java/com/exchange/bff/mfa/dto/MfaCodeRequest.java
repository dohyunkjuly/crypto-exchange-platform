package com.exchange.bff.mfa.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * A six-digit TOTP code, or a recovery code -- the service tells them apart by shape, so
 * one field covers both and a user pasting a recovery code into the ordinary box works.
 *
 * <p>Deliberately no length or pattern constraint. Rejecting a malformed code here would
 * answer faster for a wrong shape than for a wrong code, which is a timing signal about
 * what the server expects; {@code MfaService} treats every failure identically instead.
 */
public record MfaCodeRequest(@NotBlank String code) {
}
