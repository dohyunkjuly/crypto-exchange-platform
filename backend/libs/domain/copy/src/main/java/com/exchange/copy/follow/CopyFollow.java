package com.exchange.copy.follow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
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
 * One follower copying one leader: the copy account and how it is going
 * (docs/reference/copy-trading.md).
 *
 * <p>Versioned, because two writers race on it: the copier counting a failure or a profit, and
 * the follower stopping or changing funds (docs/reference/entities.md).
 */
@Entity
@Table(name = "copy_follows")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyFollow implements Persistable<UUID> {

    /** What the follow form opens at, and the range a follower may choose from: 0.1% to 5%. Below
     *  it a buy rarely fills at all; above it a follower pays far beyond the leader in a thin book. */
    public static final int DEFAULT_SLIPPAGE_CAP_BPS = 50;
    public static final int MIN_SLIPPAGE_CAP_BPS = 10;
    public static final int MAX_SLIPPAGE_CAP_BPS = 500;

    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private UUID leaderId;

    /** The follower, a master. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    /** Their COPY sub-account for this leader. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long copyUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private CopyMode mode;

    /** {@link CopyMode#FIXED_AMOUNT} only: what each copied buy costs, USDT scaled by 1e8. */
    private Long amountPerOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FollowStatus status = FollowStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private CloseReason closeReason;

    private Instant closedAt;

    @Column(nullable = false)
    private int failuresInRow;

    /** Net of fees and signed: a copy account can be down. */
    @Column(nullable = false)
    private long realizedProfit;

    /** The leader's part of {@link #realizedProfit}, frozen when the follower started (V21). */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int profitShareBps;

    /** How far above the leader's average price this follower's copied buys may fill, in basis
     *  points (docs/reference/copy-trading.md). The follower's own choice; sells are never capped. */
    @Column(nullable = false)
    private int slippageCapBps = DEFAULT_SLIPPAGE_CAP_BPS;

    /** What the leader has been paid of {@link #realizedProfit}. Only ever grows. */
    @Column(nullable = false)
    private long sharedProfit;

    @Version
    @Setter(AccessLevel.NONE)
    private long version;

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

    /** @param amountPerOrder null unless {@code mode} is {@link CopyMode#FIXED_AMOUNT} */
    /** @param profitShareBps the rate the follower agreed to; it never changes for this account */
    public CopyFollow(UUID id, UUID leaderId, Long userId, Long copyUserId, CopyMode mode, Long amountPerOrder,
                      int profitShareBps) {
        this.id = id;
        this.leaderId = leaderId;
        this.userId = userId;
        this.copyUserId = copyUserId;
        this.mode = mode;
        this.amountPerOrder = amountPerOrder;
        this.profitShareBps = profitShareBps;
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
        if (!(o instanceof CopyFollow other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
