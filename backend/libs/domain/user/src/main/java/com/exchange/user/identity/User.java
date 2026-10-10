package com.exchange.user.identity;

import com.exchange.user.kyc.KycLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "user_users")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /** Random, never counted: {@link RandomUidGenerator}. */
    @Id
    @RandomUid
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false)
    private boolean emailVerified = false;

    /** Null for an account that has only ever signed in through a provider -- see V1__user.sql.
     *  Null means "no password login", never "empty password". */
    @Column(length = 255)
    private String passwordHash;

    @Column(length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status = UserStatus.ACTIVE;

    /** Why it is SUSPENDED, in an admin's words; null while it is not. V10__user_freeze.sql. */
    @Column(length = 255)
    private String frozenReason;

    /** Frozen because its master was, rather than on its own -- so unfreezing the master may
     *  release it. Never true on a PRIMARY row. */
    @Column(nullable = false)
    private boolean frozenWithMaster = false;

    /** Set while an admin has suspended trading, with the admin's reason. The account is
     *  otherwise ACTIVE. V13__user_trading_suspension.sql. */
    private Instant tradingSuspendedAt;

    @Column(length = 255)
    private String tradingSuspendedReason;

    /**
     * The gate's read model -- see V1__user.sql. What a request is allowed to do, on the row that
     * is already loaded to authenticate it, so checking costs no extra query.
     *
     * <p>{@code user_kyc_profiles} and {@code user_mfa_methods} remain the record; these
     * three are a summary of them, written in the same transaction by {@code KycService}
     * and {@code MfaService}. Nothing else may write them: drift here is not a stale
     * cache, it is someone trading who should not be able to.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KycLevel kycLevel = KycLevel.NONE;

    /** When {@link #kycLevel} stops counting; null if it does not expire. A timestamp
     *  rather than a status, because verification lapses with no write to notice it. */
    private Instant kycVerifiedUntil;

    @Column(nullable = false)
    private boolean mfaEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserType type = UserType.PRIMARY;

    /** What a SUB is for; STANDARD on every other row. V17__copy_trading.sql. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubPurpose subPurpose = SubPurpose.STANDARD;

    private Long parentUserId;

    // No kycProfileId: user_kyc_profiles.user_id is unique and points this way, so a
    // column here would be a second copy of the same relationship. See V1__user.sql.

    private Long tradeGroupId;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    /**
     * Whether anything may ever turn this row into a session. A sub-account never can: it has
     * no password, and neither a Google token for its address nor a password reset (whose
     * code its master would receive) may give it one. docs/reference/sub-accounts.md.
     */
    public boolean canLogIn() {
        return status == UserStatus.ACTIVE && type == UserType.PRIMARY;
    }

    public User(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
    }

    // Hand-written rather than @EqualsAndHashCode: a new entity has a null id and gets
    // one on save, so a field-derived hashCode would change while the object sits in a set.
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof User other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    public boolean isTradingSuspended() {
        return tradingSuspendedAt != null;
    }
}
