package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The second call of a login: the challenge handed back by the first, plus a TOTP or
 *  recovery code. */
public record MfaLoginRequest(@NotBlank String challengeToken, @NotBlank String code) {
}
