package com.exchange.bff.apikey.dto;

import com.exchange.user.apikey.UserApiKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Never the secret -- that's only ever in {@link CreatedApiKeyView}, once, at creation.
 *  {@code list()} returns every key a user has ever created, revoked ones included (useful
 *  history, not just the active set) -- {@code revokedAt}/{@code revokedReason} are what
 *  let the client tell them apart. */
public record ApiKeyView(UUID id, String apiKey, String label, boolean canRead,
                         boolean canTrade, boolean canWithdraw, List<String> ipAllowlist,
                         Instant lastUsedAt, Instant expiresAt, Instant createdAt,
                         Instant revokedAt, String revokedReason) {

    /** The allowlist column is JSON text written by libs/domain/user, which has no JSON
     *  library of its own; Jackson lives here in web-bff, so it is parsed on this side. */
    public static ApiKeyView of(UserApiKey key, ObjectMapper json) {
        if (key.getIpAllowlist() == null) {
            return of(key, List.of());
        }
        try {
            return of(key, json.readValue(key.getIpAllowlist(), new TypeReference<List<String>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt ip_allowlist JSON on a stored key", e);
        }
    }

    private static ApiKeyView of(UserApiKey key, List<String> ipAllowlist) {
        return new ApiKeyView(key.getId(), key.getApiKey(), key.getLabel(), key.isCanRead(),
                key.isCanTrade(), key.isCanWithdraw(), ipAllowlist, key.getLastUsedAt(),
                key.getExpiresAt(), key.getCreatedAt(), key.getRevokedAt(),
                key.getRevokedReason() == null ? null : key.getRevokedReason().name());
    }
}
