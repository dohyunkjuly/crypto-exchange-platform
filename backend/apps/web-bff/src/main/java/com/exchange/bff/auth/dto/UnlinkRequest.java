package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The account's password, as proof the user still has the credential they are about to
 * fall back on. Not a second factor: unlinking takes a login method away rather than
 * weakening 2FA, and the password is the thing whose continued usefulness is in question.
 */
public record UnlinkRequest(@NotBlank String password) {
}
