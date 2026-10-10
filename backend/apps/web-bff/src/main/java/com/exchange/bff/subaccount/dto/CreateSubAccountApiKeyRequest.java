package com.exchange.bff.subaccount.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** No {@code canWithdraw}: a sub-account's key never withdraws. The code is the master's. */
public record CreateSubAccountApiKeyRequest(@NotBlank @Size(max = 64) String label, boolean canRead,
                                            boolean canTrade, List<String> ipAllowlist, Instant expiresAt,
                                            @NotBlank String mfaCode) {
}
