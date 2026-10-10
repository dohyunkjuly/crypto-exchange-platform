package com.exchange.copy.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A lead account at the end of one UTC day, valued in the copy asset (V20). Written once: a
 * day that is over is a fact, and a return is read from the chain of them
 * (docs/reference/copy-trading.md).
 */
@Entity
@Table(name = "copy_leader_snapshots")
@IdClass(CopyLeaderSnapshot.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyLeaderSnapshot {

    @Id
    private UUID leaderId;

    @Id
    private LocalDate day;

    @Column(nullable = false)
    private long equity;

    /** In minus out since the snapshot before: taken off before two snapshots are compared. */
    @Column(nullable = false)
    private long netTransfers;

    @Column(nullable = false)
    private Instant takenAt;

    public CopyLeaderSnapshot(UUID leaderId, LocalDate day, long equity, long netTransfers, Instant takenAt) {
        this.leaderId = leaderId;
        this.day = day;
        this.equity = equity;
        this.netTransfers = netTransfers;
        this.takenAt = takenAt;
    }

    /** JPA needs the composite key as its own type; nothing else uses it. */
    public static class Key implements Serializable {
        private UUID leaderId;
        private LocalDate day;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(leaderId, other.leaderId) && Objects.equals(day, other.day);
        }

        @Override
        public int hashCode() {
            return Objects.hash(leaderId, day);
        }
    }
}
