package com.exchange.wallet.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/** One internal transfer, written once. The id is assigned, so {@link Persistable} as {@code SpotOrder}. */
@Entity
@Table(name = "wallet_transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransfer implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long fromUserId;

    @Column(nullable = false)
    private Long toUserId;

    @Column(nullable = false)
    private Long assetId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private Long requestedBy;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public WalletTransfer(UUID id, Long fromUserId, Long toUserId, Long assetId, long amount, Long requestedBy) {
        this.id = id;
        this.fromUserId = fromUserId;
        this.toUserId = toUserId;
        this.assetId = assetId;
        this.amount = amount;
        this.requestedBy = requestedBy;
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
