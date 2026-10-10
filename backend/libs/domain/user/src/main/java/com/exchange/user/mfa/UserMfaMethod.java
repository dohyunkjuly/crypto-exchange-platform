package com.exchange.user.mfa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

@Entity
@Table(name = "user_mfa_methods")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserMfaMethod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private MfaMethodType methodType;

    @Column(length = 64)
    private String label;

    /** TOTP only. Encrypted, never hashed — checking a code recomputes it. */
    @Setter(AccessLevel.NONE)
    private byte[] secretEncrypted;

    @Column(length = 32)
    @Setter(AccessLevel.NONE)
    private String secretKeyId;

    /** WebAuthn only. */
    @Setter(AccessLevel.NONE)
    private byte[] credentialId;

    @Setter(AccessLevel.NONE)
    private byte[] publicKey;

    /**
     * WebAuthn replay defence. Must never decrease — if an assertion arrives with a count
     * at or below the stored one, the authenticator has been cloned.
     */
    private Long signCount;

    /** Enrolled is not active. Nothing counts until the user proves they can use it. */
    private Instant verifiedAt;

    private Instant lastUsedAt;

    /**
     * The RFC 6238 time step this method last authenticated with -- see V1__user.sql.
     *
     * <p>What makes a TOTP code single-use. A code is valid for its own 30-second step
     * plus the drift window either side, so without this a code read off someone's screen
     * stays a working second factor for up to 90 seconds after they used it. A new code
     * must land on a strictly greater step than this one.
     */
    private Long lastUsedStep;

    private Instant revokedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public static UserMfaMethod totp(Long userId, String label,
                                     byte[] secretEncrypted, String secretKeyId) {
        UserMfaMethod method = new UserMfaMethod();
        method.userId = userId;
        method.label = label;
        method.methodType = MfaMethodType.TOTP;
        method.secretEncrypted = secretEncrypted;
        method.secretKeyId = secretKeyId;
        return method;
    }

    public static UserMfaMethod webauthn(Long userId, String label,
                                         byte[] credentialId, byte[] publicKey, long signCount) {
        UserMfaMethod method = new UserMfaMethod();
        method.userId = userId;
        method.label = label;
        method.methodType = MfaMethodType.WEBAUTHN;
        method.credentialId = credentialId;
        method.publicKey = publicKey;
        method.signCount = signCount;
        return method;
    }

    public boolean isUsable(Instant now) {
        return verifiedAt != null && revokedAt == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserMfaMethod other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
