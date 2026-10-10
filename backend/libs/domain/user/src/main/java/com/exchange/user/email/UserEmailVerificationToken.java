package com.exchange.user.email;

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

/**
 * One emailed code that proves an address is reachable.
 *
 * <p>A code rather than a link, deliberately -- see V1__user.sql. The consequence is that six digits
 * carry almost no entropy, so every other protection has to do the work: short expiry,
 * single use, scoped to one user, and a hard cap on wrong guesses.
 *
 * <p>Still hashed, though the honest reason is narrower than for a session token: a
 * six-digit hash falls to brute force in milliseconds, so this protects against a code
 * leaking through a log, a backup or a read replica -- not against someone holding the
 * whole table.
 */
@Entity
@Table(name = "user_email_verification_tokens")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEmailVerificationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private byte[] codeHash;

    /** Wrong guesses so far. See {@link #MAX_ATTEMPTS}. */
    @Column(nullable = false)
    private int attempts;

    /** The address this token proves, which is not necessarily the account's address by
     *  the time it is redeemed. See {@link #isRedeemableFor}. */
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

    /** Five is enough for a mistyped digit and far too few to search a million values. */
    public static final int MAX_ATTEMPTS = 5;

    public UserEmailVerificationToken(Long userId, byte[] codeHash, String email,
                                      Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.email = email;
        this.expiresAt = expiresAt;
    }

    /**
     * Unspent, unexpired, not exhausted, and issued for the address it is being used on.
     *
     * <p>The address check is the one worth spelling out: without it, someone could request
     * a code for an address they control, change the account's email to one they do not,
     * and redeem it to have that address marked verified.
     */
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
        if (!(o instanceof UserEmailVerificationToken other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
