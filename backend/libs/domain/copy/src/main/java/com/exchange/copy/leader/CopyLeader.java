package com.exchange.copy.leader;

import com.exchange.copy.settings.CopySettings;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

/**
 * An application to lead and what became of it (docs/reference/copy-trading.md).
 *
 * <p>The id is the public one, a ULID assigned by the caller: a profile's URL carries it, and
 * neither the customer's id nor their email ever does. {@code displayName} is what followers see.
 */
@Entity
@Table(name = "copy_leaders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyLeader implements Persistable<UUID> {

    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** The customer, a master. One application each. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    /** The LEAD sub-account, made when the customer first leads. */
    private Long leadUserId;

    @Column(nullable = false, length = 40)
    private String displayName;

    @Column(length = 500)
    private String bio;

    /** Whether the profile shows the lead account's holdings and trades; the figures always show. */
    @Column(nullable = false)
    private boolean showPortfolio = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LeaderStatus status = LeaderStatus.ACTIVE;

    /** This leader's share of a follower's profit, set by staff; null means the exchange's. Never
     *  the leader's own to set. Frozen on each follow when it starts. */
    private Integer profitShareBps;

    /** What someone following now agrees to pay: this leader's rate, or the exchange's. */
    public int profitShareBps(CopySettings settings) {
        return profitShareBps != null ? profitShareBps : settings.getProfitShareBps();
    }

    /** The admin who approved, rejected or suspended, and why. */
    private Long reviewedBy;

    private Instant reviewedAt;

    @Column(length = 255)
    private String reviewReason;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @Transient
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private boolean isNew = false;

    public CopyLeader(UUID id, Long userId, String displayName, String bio) {
        this.id = id;
        this.userId = userId;
        this.displayName = displayName;
        this.bio = bio;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopyLeader other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
