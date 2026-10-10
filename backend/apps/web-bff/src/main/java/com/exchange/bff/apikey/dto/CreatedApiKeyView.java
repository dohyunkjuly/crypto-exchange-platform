package com.exchange.bff.apikey.dto;

import java.util.UUID;
import com.exchange.user.apikey.dto.CreatedApiKey;

/** {@code secret} is the plaintext -- shown exactly once, here, and never again. */
public record CreatedApiKeyView(UUID id, String apiKey, String secret, String label,
                                boolean canRead, boolean canTrade, boolean canWithdraw) {

    public static CreatedApiKeyView of(CreatedApiKey created) {
        var key = created.key();
        return new CreatedApiKeyView(key.getId(), key.getApiKey(), created.secret(),
                key.getLabel(), key.isCanRead(), key.isCanTrade(), key.isCanWithdraw());
    }
}
