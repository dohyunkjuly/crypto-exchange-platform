package com.exchange.bff.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record UpdateApiKeyRequest(
        @NotBlank @Size(max = 64) String label,
        boolean canTrade,
        boolean canWithdraw,
        List<String> ipAllowlist,
        Instant expiresAt,
        @NotBlank String mfaCode) {
}
