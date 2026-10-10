package com.exchange.user.session;

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
@Table(name = "user_sessions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false, unique = true)
    @Setter(AccessLevel.NONE)
    private byte[] tokenHash;

    @Column(nullable = false, length = 45)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 128)
    private String deviceId;

    @Column(length = 2)
    private String countryCode;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private SessionRevokedReason revokedReason;

    public UserSession(Long userId, byte[] tokenHash, String ipAddress, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.ipAddress = ipAddress;
        this.expiresAt = expiresAt;
        this.lastSeenAt = Instant.now();
    }

    public void revoke(SessionRevokedReason reason, Instant at) {
        this.revokedReason = reason;
        this.revokedAt = at;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserSession other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
