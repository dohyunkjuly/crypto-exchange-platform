package com.exchange.wallet.deposit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * An incoming on-chain transfer, from first sighting to credited balance.
 *
 * <p>The unique key over (assetNetworkId, txHash, txOutputIndex) is the safety story: a
 * chain watcher polls, restarts and replays, seeing the same transaction many times.
 * Without it, every sighting is a fresh credit.
 *
 * <p>{@code txOutputIndex} exists because UTXO chains can pay us twice in one transaction,
 * so the hash alone is not unique. It is 0 on account-based chains.
 */
@Entity
@Table(name = "wallet_deposits")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long assetId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long assetNetworkId;

    @Setter(AccessLevel.NONE)
    private Long depositAddressId;

    @Column(nullable = false, length = 128)
    @Setter(AccessLevel.NONE)
    private String txHash;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int txOutputIndex;

    @Column(length = 128)
    private String fromAddress;

    /** Scaled by 1e8. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long amount;

    @Column(nullable = false)
    private int confirmations;

    /**
     * Snapshotted when the deposit was first seen. Raising the platform requirement later
     * must not change the rule for a deposit already in flight.
     */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int requiredConfirmations;

    /** Kept so a reorg can be detected — the case {@link DepositStatus#ORPHANED} is for. */
    private Long blockNumber;

    @Column(length = 128)
    private String blockHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private DepositStatus status = DepositStatus.DETECTED;

    /** The credit. Null until CREDITED; the link reconciliation checks. */
    private Long ledgerEntryId;

    @Column(nullable = false)
    private Instant detectedAt;

    private Instant creditedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public Deposit(Long userId, Long assetId, Long assetNetworkId, Long depositAddressId,
                   String txHash, int txOutputIndex, long amount, int requiredConfirmations) {
        if (amount <= 0) {
            throw new IllegalArgumentException("deposit amount must be positive");
        }
        this.userId = userId;
        this.assetId = assetId;
        this.assetNetworkId = assetNetworkId;
        this.depositAddressId = depositAddressId;
        this.txHash = txHash;
        this.txOutputIndex = txOutputIndex;
        this.amount = amount;
        this.requiredConfirmations = requiredConfirmations;
        this.detectedAt = Instant.now();
    }

    public boolean isConfirmed() {
        return confirmations >= requiredConfirmations;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Deposit other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
