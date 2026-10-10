package com.exchange.user.mfa;

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
 * One single-use code that gets a user back in when their authenticator is gone.
 *
 * <p>Stored as a SHA-256 hash, never the code itself -- the same treatment as a session
 * token and for the same reason (AGENTS.md's Credentials table): these are
 * high-entropy random strings that are only ever compared, so a plain hash is enough and
 * a work factor would just cost latency. The plaintext exists exactly once, in the
 * response that created it.
 */
@Entity
@Table(name = "user_mfa_recovery_codes")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserMfaRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private byte[] codeHash;

    /** Set on use, never cleared -- a spent code stays as evidence of when it was spent,
     *  which is what someone disputing account access will ask about. */
    private Instant usedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public UserMfaRecoveryCode(Long userId, byte[] codeHash) {
        this.userId = userId;
        this.codeHash = codeHash;
    }

    public boolean isSpent() {
        return usedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UserMfaRecoveryCode other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
