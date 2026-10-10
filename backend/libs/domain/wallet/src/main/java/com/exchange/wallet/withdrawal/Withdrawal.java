package com.exchange.wallet.withdrawal;

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
 * An outgoing transfer — the only path by which value leaves the platform, and the one
 * place where every mistake is irreversible.
 *
 * <p>Three amounts, not one: the user asks for {@code amount}, {@code fee} is what the
 * chain and the platform take, {@code netAmount} is what is actually broadcast. Storing
 * two of the three is how somebody eventually subtracts in the wrong direction. A CHECK
 * constraint keeps them consistent.
 */
@Entity
@Table(name = "wallet_withdrawals")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Withdrawal {

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

    @Column(nullable = false, length = 128)
    @Setter(AccessLevel.NONE)
    private String toAddress;

    @Column(nullable = false, length = 64)
    @Setter(AccessLevel.NONE)
    private String memo = "";

    /** All scaled by 1e8. amount = netAmount + fee. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long amount;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long fee;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private WithdrawalStatus status = WithdrawalStatus.REQUESTED;

    @Column(length = 128)
    private String txHash;

    private Long blockNumber;

    @Column(nullable = false)
    private int confirmations;

    /** The two-person rule — the last defence once an account is already compromised. */
    @Column(nullable = false)
    private boolean requiresManualReview = false;

    private Long approvedBy;

    private Instant approvedAt;

    @Column(length = 255)
    private String rejectionReason;

    /** Client-supplied. A retried request returns the original rather than sending twice. */
    @Column(nullable = false, unique = true, length = 128)
    @Setter(AccessLevel.NONE)
    private String idempotencyKey;

    /** The debit. Null until the funds actually leave the balance. */
    private Long ledgerEntryId;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant completedAt;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    public Withdrawal(Long userId, Long assetId, Long assetNetworkId,
                      String toAddress, String memo,
                      long amount, long fee, String idempotencyKey) {
        if (amount <= 0 || fee < 0 || amount <= fee) {
            throw new IllegalArgumentException(
                    "amount must be positive and exceed the fee: amount=" + amount + " fee=" + fee);
        }
        this.userId = userId;
        this.assetId = assetId;
        this.assetNetworkId = assetNetworkId;
        this.toAddress = toAddress;
        this.memo = memo == null ? "" : memo;
        this.amount = amount;
        this.fee = fee;
        this.netAmount = amount - fee;
        this.idempotencyKey = idempotencyKey;
        this.requestedAt = Instant.now();
    }

    /** Before broadcast a withdrawal can still be stopped. After it, nothing can. */
    public boolean isCancellable() {
        return status == WithdrawalStatus.REQUESTED
                || status == WithdrawalStatus.PENDING_APPROVAL
                || status == WithdrawalStatus.APPROVED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Withdrawal other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
