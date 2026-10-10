package com.exchange.bff.auth.google;

/**
 * Turns the ID token a browser got from Google into something we are willing to believe.
 *
 * <p>An interface so tests can hand back a known identity without reaching Google -- a
 * signup test that needs network access to a third party is a test that fails for reasons
 * unrelated to the code.
 */
public interface GoogleTokenVerifier {

    /**
     * @return the identity the token asserts
     * @throws InvalidGoogleTokenException the token is unsigned by Google, expired, or
     *         issued for a different application
     */
    GoogleIdentity verify(String idToken);
}
