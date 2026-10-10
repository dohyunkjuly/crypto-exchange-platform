package com.exchange.copy.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * What staff set for copy trading across the whole exchange (docs/reference/copy-trading.md).
 * Exactly one row, {@link #SINGLETON_ID}, held by a check constraint and made by the migration.
 * Amounts are USDT, scaled by 1e8.
 */
@Entity
@Table(name = "copy_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopySettings {

    public static final int SINGLETON_ID = 1;

    @Id
    @Column(columnDefinition = "TINYINT UNSIGNED")
    @Setter(AccessLevel.NONE)
    private Integer id;

    /** The leader's part of a follower's realized profit. */
    @Column(nullable = false)
    private int profitShareBps;

    /** Failed copies in a row that close a copy account. */
    @Column(nullable = false)
    private int failureLimit;

    @Column(nullable = false)
    private int maxFollowersPerLeader;

    @Column(nullable = false)
    private int maxCopiesPerUser;

    @Column(nullable = false)
    private long minFixedRatioAmount;

    @Column(nullable = false)
    private long minFixedAmount;

    /** What a lead account must keep; only what is above it can be taken out. */
    @Column(nullable = false)
    private long leadMinBalance;

    /** The most a leader may put behind their lead account (V23). */
    @Column(nullable = false)
    private long leadMaxBalance;

    /** On closing, a holding worth less than this is returned as it is rather than sold. */
    @Column(nullable = false)
    private long closeSellThreshold;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CopySettings other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
