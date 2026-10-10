package com.exchange.spot.orderfilllog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * Marks that one order has already applied one trade -- not for correctness (a fill is
 * already safe to apply twice without corrupting anything, see {@code
 * SpotOrderRepository#applyFillAtomically}'s own javadoc), but so that many trades'
 * deltas for the SAME order can be summed and written as a single aggregate update
 * instead of one write per trade. {@code spot_trades}' own {@code (symbol_id,
 * sequence)} uniqueness answers "has this trade happened at all"; this table answers a
 * different question -- "has this SPECIFIC order already counted it" -- which stops
 * being the same question the moment a trade's two sides can be batched into two
 * entirely separate aggregate writes rather than landing together.
 *
 * <p>Write-only and read-by-existence-only: nothing ever updates a row here, and
 * nothing ever needs to load one back out as a rich object -- see {@code
 * SpotOrderFillLogRepository#findExistingTradeIds}.
 */
@Entity
@Table(name = "spot_order_fill_log")
@Getter
@IdClass(SpotOrderFillLog.Key.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotOrderFillLog implements Persistable<SpotOrderFillLog.Key> {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Id
    @Column(name = "trade_id")
    private UUID tradeId;

    // Set in Java, not left to the column's DB-side DEFAULT -- see AGENTS.md:
    // "Java-side defaults are what fire, not SQL defaults, Hibernate puts every column
    // in the INSERT." The DB default still exists as a backstop for anything that ever
    // writes here outside this entity, but this is what actually runs.
    //
    // This is also, empirically, what makes saveAll(...) batchable at all: the
    // opposite choice -- @Generated(event = INSERT), reading the DB-computed value
    // back after each individual insert -- defeats JDBC batching exactly the way an
    // IDENTITY-generated id does, confirmed by checking Hibernate's own batch
    // statistics before and after this change, not assumed from how the annotation
    // reads.
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public SpotOrderFillLog(UUID orderId, UUID tradeId) {
        this.orderId = orderId;
        this.tradeId = tradeId;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    @Override
    public Key getId() {
        return new Key(orderId, tradeId);
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
        if (!(o instanceof SpotOrderFillLog other)) {
            return false;
        }
        return orderId != null && orderId.equals(other.orderId)
                && tradeId != null && tradeId.equals(other.tradeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }

    /** A plain class, not a record -- {@code @IdClass} traditionally wants a no-arg
     *  constructor and a hand-written {@code equals}/{@code hashCode}, and this table
     *  is exactly the kind of place not to gamble that a newer JPA-mapping feature
     *  behaves the way a quick read of its docs suggests (see {@code
     *  SpotOrderRepository#applyFillAtomically}'s own javadoc for a case this session
     *  where that gamble did not pay off). */
    public static class Key implements Serializable {
        private UUID orderId;
        private UUID tradeId;

        public Key() {
        }

        public Key(UUID orderId, UUID tradeId) {
            this.orderId = orderId;
            this.tradeId = tradeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(orderId, other.orderId) && Objects.equals(tradeId, other.tradeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, tradeId);
        }
    }
}
