package com.exchange.bff.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What {@code POST /bff/auth/login} answers, in both shapes.
 *
 * <pre>
 *   { "mfaRequired": false, "user": { ... } }                  -- signed in, cookie set
 *   { "mfaRequired": true,  "challengeToken": "..." }          -- send the code next
 * </pre>
 *
 * <p>One response rather than two status codes, because the client's next move is a branch
 * either way and {@code mfaRequired} is the thing it branches on. The unused half is
 * omitted rather than sent null, so no client can accidentally read a {@code user} that
 * is not signed in yet.
 *
 * <p><b>No session cookie accompanies the challenge shape.</b> Nothing is authenticated
 * until the code arrives.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginView(boolean mfaRequired, String challengeToken, UserView user) {

    public static LoginView signedIn(UserView user) {
        return new LoginView(false, null, user);
    }

    public static LoginView challenge(String challengeToken) {
        return new LoginView(true, challengeToken, null);
    }
}
