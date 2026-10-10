package com.exchange.user.apikey;

import com.exchange.user.apikey.exception.ApiKeyNotFoundException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revoking and listing keys, apart from minting them. {@link ApiKeyService} needs the MFA
 * service and the key-encryption secret to create a key; an app that only ever revokes one
 * (admin-bff) should not have to carry either, so this is the half it can depend on.
 */
@Service
public class ApiKeyRevocationService {

    private final UserApiKeyRepository apiKeys;
    private final UserAuthEventRepository authEvents;

    public ApiKeyRevocationService(UserApiKeyRepository apiKeys, UserAuthEventRepository authEvents) {
        this.apiKeys = apiKeys;
        this.authEvents = authEvents;
    }

    public List<UserApiKey> list(Collection<Long> userIds) {
        return userIds.isEmpty() ? List.of() : apiKeys.findByUserIdInOrderByCreatedAtDesc(userIds);
    }

    /**
     * Revoking is a flag, not a delete: the row stays for the audit trail. Takes effect within a
     * minute on the API, including on connections already open (api-signing-and-limits.md).
     *
     * @throws ApiKeyNotFoundException no such key, or not this user's
     */
    @Transactional
    public void revoke(Long userId, UUID keyId, ApiKeyRevokedReason reason, String ipAddress) {
        UserApiKey key = apiKeys.findById(keyId)
                .filter(k -> k.getUserId().equals(userId))
                .orElseThrow(ApiKeyNotFoundException::new);
        key.revoke(reason, Instant.now());

        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(AuthEventType.API_KEY_REVOKED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());
    }
}
