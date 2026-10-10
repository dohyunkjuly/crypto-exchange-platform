package com.exchange.bff.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * @param mfaCode a current TOTP code, or a recovery code. Required even though the account
 *                already has 2FA enabled: enabled proves enrolment, this proves the person
 *                asking has the device in hand right now. A session alone must not be
 *                enough to mint a credential that outlives it.
 */
public record CreateApiKeyRequest(
        @NotBlank @Size(max = 64) String label,
        boolean canRead,
        boolean canTrade,
        boolean canWithdraw,
        List<String> ipAllowlist,
        Instant expiresAt,
        @NotBlank String mfaCode) {
}
