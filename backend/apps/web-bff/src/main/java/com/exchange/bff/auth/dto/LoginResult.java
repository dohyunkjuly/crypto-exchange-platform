package com.exchange.bff.auth.dto;

import com.exchange.user.identity.User;
import java.time.Instant;

/** What a successful login hands the controller: the user, the raw session token to put in
 *  the cookie (never stored anywhere -- only its hash is), and when that session expires. */
public record LoginResult(User user, String sessionToken, Instant expiresAt) {
}
