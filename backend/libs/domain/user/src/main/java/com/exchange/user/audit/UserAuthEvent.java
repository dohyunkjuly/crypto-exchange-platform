package com.exchange.user.audit;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Append-only. No setters, and nothing here may be updated or deleted once written —
 * that is the property that makes it usable as evidence.
 */
@Entity
@Table(name = "user_auth_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class UserAuthEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null when a login is attempted against an address that is not registered. */
    private Long userId;

    @Column(length = 255)
    private String emailAttempted;

    private Long sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    private AuthEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuthOutcome outcome;

    @Column(length = 64)
    private String failureReason;

    /** Null for an event that did not come from a request -- a provider webhook, or an
     *  internal decision. See V1__user.sql for why that is NULL and not a sentinel. */
    @Column(length = 45)
    private String ipAddress;

    @Column(length = 512)
    private String userAgent;

    @Column(length = 2)
    private String countryCode;

    @JdbcTypeCode(SqlTypes.JSON)
    private String metadata;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserAuthEvent other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
