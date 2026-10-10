package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Asks for a reset code. Answers identically whether or not the address has an account. */
public record PasswordResetRequest(@NotBlank @Email String email) {
}
