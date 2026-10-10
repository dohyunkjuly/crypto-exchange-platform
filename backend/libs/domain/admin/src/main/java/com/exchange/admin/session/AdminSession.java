package com.exchange.admin.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One admin login. The cookie holds the raw token; this row holds its SHA-256. */
@Entity
@Table(name = "admin_sessions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long adminId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private byte[] tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdminSessionState state;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private String ipAddress;

    @Setter(AccessLevel.NONE)
    private String userAgent;

    @Column(nullable = false, insertable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    public AdminSession(Long adminId, byte[] tokenHash, String ipAddress, String userAgent, Instant now,
                        Instant expiresAt) {
        this.adminId = adminId;
        this.tokenHash = tokenHash;
        this.state = AdminSessionState.PENDING;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
    }

    public boolean isLive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
