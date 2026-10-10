package com.exchange.bff.auth.dto;

/**
 * What a correct password earns: a session, or a demand for the second factor.
 *
 * <p>Exactly one of the two is set. A password alone is no longer proof of anything for
 * an account with 2FA enabled -- which is the point of enabling it, and was not true of
 * this app until login became two calls.
 *
 * @param session        the minted session, when no second factor is required
 * @param challengeToken the handle for the second call, when one is
 */
public record LoginOutcome(LoginResult session, String challengeToken) {

    public static LoginOutcome authenticated(LoginResult session) {
        return new LoginOutcome(session, null);
    }

    public static LoginOutcome mfaRequired(String challengeToken) {
        return new LoginOutcome(null, challengeToken);
    }

    public boolean isMfaRequired() {
        return challengeToken != null;
    }
}
