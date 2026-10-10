package com.exchange.user.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_api_keys")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserApiKey {

    /** A ULID, never a counter: the web carries it in key lists and URLs (V15). */
    @Id
    @UlidId
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String label;

    @Column(nullable = false, unique = true, length = 64)
    @Setter(AccessLevel.NONE)
    private String apiKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private ApiKeyType keyType;

    /** HMAC only. Encrypted, never hashed — verification recomputes the signature. */
    @Setter(AccessLevel.NONE)
    private byte[] secretEncrypted;

    /** Which key encrypted the above, so keys can rotate without a flag day. */
    @Column(length = 32)
    @Setter(AccessLevel.NONE)
    private String secretKeyId;

    /** Ed25519 only. We never hold the private half. */
    @Setter(AccessLevel.NONE)
    private byte[] publicKey;

    @Column(nullable = false)
    private boolean canRead = true;

    @Column(nullable = false)
    private boolean canTrade = false;

    @Column(nullable = false)
    private boolean canWithdraw = false;

    /** JSON array of CIDRs. Required by a CHECK constraint when canWithdraw is true. */
    @JdbcTypeCode(SqlTypes.JSON)
    private String ipAllowlist;

    private Instant lastUsedAt;

    private Instant expiresAt;

    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ApiKeyRevokedReason revokedReason;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public static UserApiKey hmac(Long userId, String label, String apiKey,
                                  byte[] secretEncrypted, String secretKeyId) {
        UserApiKey key = new UserApiKey();
        key.userId = userId;
        key.label = label;
        key.apiKey = apiKey;
        key.keyType = ApiKeyType.HMAC_SHA256;
        key.secretEncrypted = secretEncrypted;
        key.secretKeyId = secretKeyId;
        return key;
    }

    public static UserApiKey ed25519(Long userId, String label, String apiKey, byte[] publicKey) {
        UserApiKey key = new UserApiKey();
        key.userId = userId;
        key.label = label;
        key.apiKey = apiKey;
        key.keyType = ApiKeyType.ED25519;
        key.publicKey = publicKey;
        return key;
    }

    public void revoke(ApiKeyRevokedReason reason, Instant at) {
        this.revokedReason = reason;
        this.revokedAt = at;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserApiKey other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
