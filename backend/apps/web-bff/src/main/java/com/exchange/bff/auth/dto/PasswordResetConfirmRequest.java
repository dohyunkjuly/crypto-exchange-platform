package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param mfaCode required only when the account has a second factor -- a TOTP or a
 *                recovery code. Optional in the request shape because the caller has no
 *                way to know beforehand, and asking every user for one would be worse
 *                than the round trip
 */
public record PasswordResetConfirmRequest(
        @NotBlank @Email String email,
        @NotBlank String code,
        @NotBlank @Size(min = 12) String newPassword,
        String mfaCode) {
}
