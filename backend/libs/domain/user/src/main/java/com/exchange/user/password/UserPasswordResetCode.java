package com.exchange.user.password;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** One emailed code that lets someone set a new password. See V1__user.sql. */
@Entity
@Table(name = "user_password_reset_codes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPasswordResetCode {

    /** Enough for a mistyped digit, far too few to search a million values. */
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private byte[] codeHash;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public UserPasswordResetCode(Long userId, byte[] codeHash, String email,
                                 Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.email = email;
        this.expiresAt = expiresAt;
    }

    /** Unspent, unexpired, not exhausted, and issued for the address the account still
     *  has -- otherwise whoever holds a since-replaced inbox could complete a reset. */
    public boolean isRedeemableFor(String currentEmail, Instant now) {
        return usedAt == null
                && attempts < MAX_ATTEMPTS
                && expiresAt.isAfter(now)
                && email.equalsIgnoreCase(currentEmail);
    }

    public void recordFailedAttempt() {
        attempts++;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserPasswordResetCode other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
