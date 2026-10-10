package com.exchange.bff.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** The ID token Google's sign-in button hands the browser. Nothing else is taken from the
 *  client -- not the email, not the name -- because only the token is signed. */
public record GoogleSignInRequest(@NotBlank String idToken) {
}
