package com.exchange.bff.auth.dto;

import com.exchange.user.identity.external.UserExternalIdentity;
import java.time.Instant;

/**
 * One sign-in provider attached to the account.
 *
 * <p>{@code email} is what the provider said when the link was made, and can differ from
 * both the account's address and the provider's current one -- people change their Gmail
 * address and the link survives it, because the subject identifies them, not the address.
 * Shown so someone with two Google accounts can tell which one this is.
 */
public record LinkedIdentityView(String provider, String email, Instant linkedAt,
                                 Instant lastLoginAt) {

    public static LinkedIdentityView of(UserExternalIdentity identity) {
        return new LinkedIdentityView(identity.getProvider().name(), identity.getEmail(),
                identity.getLinkedAt(), identity.getLastLoginAt());
    }
}
