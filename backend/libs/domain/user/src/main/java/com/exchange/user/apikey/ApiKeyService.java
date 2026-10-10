package com.exchange.user.apikey;

import com.exchange.common.crypto.AesGcmCipher;
import com.exchange.user.apikey.dto.CreatedApiKey;
import com.exchange.user.eligibility.EligibilityService;
import com.exchange.user.mfa.MfaService;
import com.exchange.user.apikey.exception.ApiKeyNotFoundException;
import com.exchange.user.apikey.exception.WithdrawalRequiresAllowlistException;
import com.exchange.user.audit.AuthEventType;
import com.exchange.user.audit.AuthOutcome;
import com.exchange.user.audit.UserAuthEvent;
import com.exchange.user.audit.UserAuthEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared by web-bff (the UI settings page) and open-api (programmatic self-service) --
 *  see the module's build file for why this lives here instead of in either app. */
@Service
public class ApiKeyService {

    /** Bumped if the encryption key ever rotates -- see {@code secret_key_id} in V1__user.sql. */
    private static final String KEY_ID = "v1";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final EligibilityService eligibility;
    private final MfaService mfa;
    private final UserApiKeyRepository apiKeys;
    private final UserAuthEventRepository authEvents;
    private final AesGcmCipher cipher;

    public ApiKeyService(EligibilityService eligibility, MfaService mfa,
                         UserApiKeyRepository apiKeys, UserAuthEventRepository authEvents,
                         @Value("${API_KEY_ENCRYPTION_KEY}") String base64EncryptionKey) {
        this.eligibility = eligibility;
        this.mfa = mfa;
        this.apiKeys = apiKeys;
        this.authEvents = authEvents;
        this.cipher = new AesGcmCipher(Base64.getDecoder().decode(base64EncryptionKey));
    }

    /**
     * HMAC_SHA256 only in this pass -- ED25519 needs client-side keypair generation UX,
     * deferred.
     *
     * @throws WithdrawalRequiresAllowlistException canWithdraw is true with no allowlist
     * @throws com.exchange.user.mfa.exception.InvalidMfaCodeException the step-up code is
     *         wrong, expired, or already spent -- including reusing the code from a key
     *         created seconds earlier, since a TOTP step is consumed on use
     */
    @Transactional
    public CreatedApiKey create(Long userId, String label, boolean canRead, boolean canTrade,
                                boolean canWithdraw, List<String> ipAllowlist, Instant expiresAt,
                                String mfaCode, String ipAddress) {
        return create(userId, userId, label, canRead, canTrade, canWithdraw, ipAllowlist, expiresAt, mfaCode,
                ipAddress);
    }

    /**
     * A key for a sub-account, minted by its master: the gate and the step-up are the
     * master's, since a sub has no verification and no second factor of its own; the key is
     * the sub's, so a request it signs is the sub's. The caller has already checked the two
     * belong together ({@code SubAccountService}). Never withdrawal-capable.
     */
    @Transactional
    public CreatedApiKey createForSubAccount(Long masterId, Long subId, String label, boolean canRead,
                                             boolean canTrade, List<String> ipAllowlist, Instant expiresAt,
                                             String mfaCode, String ipAddress) {
        return create(masterId, subId, label, canRead, canTrade, false, ipAllowlist, expiresAt, mfaCode,
                ipAddress);
    }

    private CreatedApiKey create(Long gatedId, Long userId, String label, boolean canRead, boolean canTrade,
                                 boolean canWithdraw, List<String> ipAllowlist, Instant expiresAt,
                                 String mfaCode, String ipAddress) {
        // Before anything is minted: an API key trades unattended and outlives the
        // session that created it, so it sits behind a higher bar than trading by hand.
        eligibility.requireCanCreateApiKey(gatedId);
        // A read-only key is still allowed: a suspended account may watch its balances.
        if (canTrade) {
            eligibility.requireTradingNotSuspended(userId);
        }
        // Step-up: having 2FA enabled is not the same as proving you hold it right now.
        // A stolen session is enough to mint a key that keeps trading long after the
        // session is revoked and the password changed -- the code is what makes that
        // require the phone as well. Checked after eligibility so someone who has not
        // enrolled is told to enrol rather than asked for a code they cannot produce.
        mfa.challenge(gatedId, mfaCode, ipAddress);
        if (canWithdraw && (ipAllowlist == null || ipAllowlist.isEmpty())) {
            throw new WithdrawalRequiresAllowlistException();
        }

        String publicId = "ak_" + randomToken(18);
        String secret = randomToken(32);
        byte[] encryptedSecret = cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8));

        UserApiKey key = UserApiKey.hmac(userId, label, publicId, encryptedSecret, KEY_ID);
        key.setCanRead(canRead);
        key.setCanTrade(canTrade);
        key.setCanWithdraw(canWithdraw);
        key.setIpAllowlist(toJsonArray(ipAllowlist));
        key.setExpiresAt(expiresAt);
        key = apiKeys.save(key);

        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(AuthEventType.API_KEY_CREATED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());

        return new CreatedApiKey(key, secret);
    }

    public List<UserApiKey> list(Long userId) {
        return apiKeys.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Several accounts at once; the caller decides whose ids it is allowed to ask for. */
    public List<UserApiKey> list(Collection<Long> userIds) {
        return userIds.isEmpty() ? List.of() : apiKeys.findByUserIdInOrderByCreatedAtDesc(userIds);
    }

    /** The key's secret, recovered to recompute a signature -- the reason it is encrypted rather
     *  than hashed (AGENTS.md's credentials table). Only a signature check should ever call this;
     *  the value must never reach a log or a response. */
    public String decryptSecret(UserApiKey key) {
        if (key.getKeyType() != ApiKeyType.HMAC_SHA256 || key.getSecretEncrypted() == null) {
            throw new IllegalArgumentException("key " + key.getId() + " has no HMAC secret");
        }
        return new String(cipher.decrypt(key.getSecretEncrypted()), StandardCharsets.UTF_8);
    }

    /** Records that the key was used. The caller throttles -- once a minute per key is plenty for
     *  an audit column, and once a request would be a write on every signed call. */
    @Transactional
    public void touchLastUsed(UUID keyId, Instant at) {
        apiKeys.touchLastUsed(keyId, at);
    }

    /**
     * Changes what a key may do, behind the same step-up as making one: widening a key's
     * permissions is minting a new credential in all but name. The key and secret stay as
     * they are, so nothing a bot holds has to change.
     *
     * @throws ApiKeyNotFoundException no such key, or it belongs to a different user
     * @throws WithdrawalRequiresAllowlistException withdrawing without an allowlist
     */
    @Transactional
    public UserApiKey update(Long userId, UUID keyId, String label, boolean canRead, boolean canTrade,
                             boolean canWithdraw, List<String> ipAllowlist, Instant expiresAt, String mfaCode,
                             String ipAddress) {
        UserApiKey key = apiKeys.findById(keyId)
                .filter(k -> k.getUserId().equals(userId) && k.getRevokedAt() == null)
                .orElseThrow(ApiKeyNotFoundException::new);
        // Turning trading on is refused while suspended; a key that already trades may still
        // be relabelled, since the gate stops it trading either way.
        if (canTrade && !key.isCanTrade()) {
            eligibility.requireTradingNotSuspended(userId);
        }
        mfa.challenge(userId, mfaCode, ipAddress);
        if (canWithdraw && (ipAllowlist == null || ipAllowlist.isEmpty())) {
            throw new WithdrawalRequiresAllowlistException();
        }
        key.setLabel(label);
        key.setCanRead(canRead);
        key.setCanTrade(canTrade);
        key.setCanWithdraw(canWithdraw);
        key.setIpAllowlist(toJsonArray(ipAllowlist));
        key.setExpiresAt(expiresAt);
        authEvents.save(UserAuthEvent.builder()
                .userId(userId)
                .eventType(AuthEventType.API_KEY_UPDATED)
                .outcome(AuthOutcome.SUCCESS)
                .ipAddress(ipAddress)
                .build());
        return apiKeys.save(key);
    }

    /** @throws ApiKeyNotFoundException no such key, or it belongs to a different user */
    @Transactional
    public void revoke(Long userId, UUID keyId, ApiKeyRevokedReason reason, String ipAddress) {
        new ApiKeyRevocationService(apiKeys, authEvents).revoke(userId, keyId, reason, ipAddress);
    }

    private static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** No JSON library on this module's classpath, and a list of CIDRs is simple enough
     *  not to need one. */
    private static String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"')
                    .append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return json.append(']').toString();
    }
}
