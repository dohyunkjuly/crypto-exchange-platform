package com.exchange.wallet.ledger;

import com.exchange.common.id.Ulid;
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
import org.springframework.data.domain.Persistable;

/**
 * One movement of one balance. Append-only: no setters, nothing here is ever updated or
 * deleted. A mistake is corrected with a compensating {@link LedgerEntryType#ADJUSTMENT},
 * the way an accountant does it.
 *
 * <p>{@code idempotencyKey} is the important field. Wallet learns about fills by consuming
 * Kafka, which delivers at least once — the same trade event will arrive twice, and the
 * second time must credit nothing. The unique index makes the duplicate insert fail rather
 * than inventing money.
 *
 * <p>{@code id} is an app-generated UUID, not a DB {@code IDENTITY} column, and {@code
 * createdAt} is set in Java rather than left to the column's DB-side {@code DEFAULT} --
 * both, empirically, for the same reason {@code SpotTrade} and {@code SpotOrderFillLog}
 * needed the identical fix: either one alone forces Hibernate to read a DB-computed value
 * back after each individual insert, which defeats JDBC batching the same way an {@code
 * IDENTITY} id does. This is the highest-volume insert in the system (see V2__wallet.sql's header) and the one {@code BalanceService#applyBatch} most needs to actually
 * batch, not just call {@code saveAll} and hope.
 */
@Entity
@Table(name = "wallet_ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long assetId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LedgerEntryType entryType;

    /** Signed and scaled by 1e8. Positive credits the user, negative debits them. */
    @Column(nullable = false)
    private long amount;

    /** The balance immediately after. Turns "why is this wrong" into a binary search. */
    @Column(nullable = false)
    private long balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ReferenceType referenceType;

    /** A string because it points at several kinds of thing — order ids will be ULIDs. */
    @Column(length = 64)
    private String referenceId;

    @Column(nullable = false, unique = true, length = 128)
    private String idempotencyKey;

    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public LedgerEntry(Long userId, Long assetId, LedgerEntryType entryType,
                       long amount, long balanceAfter,
                       ReferenceType referenceType, String referenceId,
                       String idempotencyKey) {
        if (amount == 0) {
            throw new IllegalArgumentException("a ledger entry of zero records nothing");
        }
        this.id = Ulid.randomUuid();
        this.userId = userId;
        this.assetId = assetId;
        this.entryType = entryType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    @Override
    public UUID getId() {
        return id;
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
        if (!(o instanceof LedgerEntry other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
