package com.exchange.spot.openapi.common.security;

import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;

/** What authentication established, stashed on the request for the controller.
 *  {@code user} is null unless the endpoint is gated or declares a {@code @CurrentUser User}:
 *  {@link ApiKeyAuthInterceptor} loads the row only for what reads it. */
public record SignedRequest(UserApiKey key, User user) {
    static final String ATTRIBUTE = "signedRequest";
}
