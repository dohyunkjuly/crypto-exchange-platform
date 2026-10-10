package com.exchange.wallet.balance;

import com.exchange.wallet.balance.exception.InsufficientBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * What one user holds of one asset, split into what they can spend and what an open order
 * has reserved.
 *
 * <p>Placing an order does not spend anything — it moves funds from available to locked.
 * Cancel and it comes back; fill and locked is debited. One combined number would make
 * "can this user afford another order" unanswerable.
 *
 * <p>A projection of {@code LedgerEntry}, which is the truth. These two numbers can always
 * be rebuilt by replaying it; they exist so the read path is one indexed row rather than a
 * sum over the user's whole history.
 *
 * <p>No setters. Every mutation goes through a method that maintains the invariants, and
 * amounts are scaled by 1e8 throughout.
 */
@Entity
@Table(name = "wallet_balances")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Balance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private long available;

    @Column(nullable = false)
    private long locked;

    /**
     * Optimistic locking. Two concurrent fills against this row would otherwise be a lost
     * update, and a lost update here is money invented or destroyed.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    private Instant updatedAt;

    public Balance(Long userId, Long assetId) {
        this.userId = userId;
        this.assetId = assetId;
    }

    public long getTotal() {
        return available + locked;
    }

    /** Reserving funds for an order. Net zero — the money is still theirs. */
    public void lock(long amount) {
        requirePositive(amount);
        if (available < amount) {
            throw new InsufficientBalanceException(userId, assetId, amount, available);
        }
        available -= amount;
        locked += amount;
    }

    /** Cancelling or expiring an order. */
    public void unlock(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new IllegalStateException(
                    "cannot unlock " + amount + "; only " + locked + " is locked");
        }
        locked -= amount;
        available += amount;
    }

    /** A fill: the locked funds actually leave. */
    public void settleLocked(long amount) {
        requirePositive(amount);
        if (locked < amount) {
            throw new IllegalStateException(
                    "cannot settle " + amount + "; only " + locked + " is locked");
        }
        locked -= amount;
    }

    public void credit(long amount) {
        requirePositive(amount);
        available += amount;
    }

    /** Withdrawals and fees, which spend directly rather than through a lock. */
    public void debit(long amount) {
        requirePositive(amount);
        if (available < amount) {
            throw new InsufficientBalanceException(userId, assetId, amount, available);
        }
        available -= amount;
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Balance other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
