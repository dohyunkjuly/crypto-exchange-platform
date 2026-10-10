package com.exchange.wallet.treasury;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import com.exchange.wallet.ledger.LedgerEntryType;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One closed hour of customer-side fee flow, per asset and type (V9__fee_rollup.sql).
 *
 * <p>Written once and never updated: an hour that is over cannot gain rows, so its total is a
 * fact rather than a cached value. {@code amount} is the signed ledger sum, so a FEE row is
 * negative and a REBATE positive -- see {@link FeeRollupService#earnedBefore}.
 */
@Entity
@Table(name = "wallet_fee_hourly_totals")
@IdClass(FeeHourlyTotal.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeeHourlyTotal {

    @Id
    private Instant periodStart;

    @Id
    private Long assetId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private LedgerEntryType entryType;

    @Column(nullable = false)
    private long amount;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    public FeeHourlyTotal(Instant periodStart, Long assetId, LedgerEntryType entryType, long amount) {
        this.periodStart = periodStart;
        this.assetId = assetId;
        this.entryType = entryType;
        this.amount = amount;
    }

    /** JPA needs the composite key as its own type; nothing else uses it. */
    public static class Key implements Serializable {
        private Instant periodStart;
        private Long assetId;
        private LedgerEntryType entryType;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(periodStart, other.periodStart)
                    && Objects.equals(assetId, other.assetId)
                    && entryType == other.entryType;
        }

        @Override
        public int hashCode() {
            return Objects.hash(periodStart, assetId, entryType);
        }
    }
}
