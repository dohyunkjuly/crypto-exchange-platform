package com.exchange.admin.identity;

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
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A member of staff. Never a customer: separate table, separate login, separate cookie.
 * docs/reference/admin.md.
 */
@Entity
@Table(name = "admin_users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AdminRole role;

    private Instant disabledAt;

    /** Set at enrolment, before the first code is checked; {@code totpConfirmedAt} says it works. */
    private byte[] totpSecretEncrypted;

    private Instant totpConfirmedAt;

    /** Single use: a code is spent for its 30-second step, so one read off a screen cannot be replayed. */
    private Long totpLastUsedStep;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public AdminUser(String email, String passwordHash, AdminRole role) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isEnabled() {
        return disabledAt == null;
    }

    public boolean isTotpConfirmed() {
        return totpConfirmedAt != null;
    }
}
