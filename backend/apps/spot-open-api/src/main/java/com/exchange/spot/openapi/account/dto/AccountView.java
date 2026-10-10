package com.exchange.spot.openapi.account.dto;

import com.exchange.user.apikey.UserApiKey;
import com.exchange.user.identity.User;
import java.time.Instant;

/**
 * Who is calling and what they may do -- the first thing a bot reads. Eligibility comes back as
 * booleans rather than as errors so "why can't I trade" is answered without a failed order.
 */
public record AccountView(Long uid, String kycLevel, boolean emailVerified, boolean mfaEnabled,
                          boolean canTrade, KeyView key, FeeRatesView fees) {

    public record KeyView(String label, boolean canRead, boolean canTrade, boolean canWithdraw,
                          Instant expiresAt) {
        public static KeyView of(UserApiKey key) {
            return new KeyView(key.getLabel(), key.isCanRead(), key.isCanTrade(), key.isCanWithdraw(),
                    key.getExpiresAt());
        }
    }

    public static AccountView of(User user, UserApiKey key, boolean canTrade, FeeRatesView fees) {
        return new AccountView(user.getId(), user.getKycLevel().name(), user.isEmailVerified(),
                user.isMfaEnabled(), canTrade, KeyView.of(key), fees);
    }
}
