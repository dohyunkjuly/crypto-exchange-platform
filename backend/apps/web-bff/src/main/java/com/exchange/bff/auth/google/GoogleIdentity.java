package com.exchange.bff.auth.google;

/**
 * What a verified Google ID token tells us about the person holding it.
 *
 * @param subject       Google's {@code sub} -- immutable, and the only thing safe to key
 *                      an account on. An email can be changed or, for Workspace domains,
 *                      reassigned to someone else entirely
 * @param email         the address on the Google account at this moment
 * @param emailVerified whether Google itself has confirmed that address. Not a formality:
 *                      it is the entire basis on which we are willing to link this to an
 *                      existing account
 */
public record GoogleIdentity(String subject, String email, boolean emailVerified) {
}
