package com.exchange.spot.trade;

import com.exchange.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * An execution: two orders crossed. The public tape, and the input to every candle, every
 * balance movement and every volume rollup.
 *
 * <p>Immutable — a trade is a fact. There are no setters and nothing corrects one; a
 * mistake becomes a compensating ledger adjustment, not an edit here.
 *
 * <p>{@code buyerIsMaker} decides who pays which rate. The maker rested on the book and
 * provided liquidity; the taker crossed the spread and removed it. The charged amounts are
 * stored rather than the rates, because the rates came from the orders — stamped at
 * acceptance — and this table never reads the fee schedule either.
 */
@Entity
@Table(name = "spot_trades")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotTrade implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long symbolId;

    /** All scaled by 1e8. */
    @Column(nullable = false)
    private long price;

    @Column(nullable = false)
    private long quantity;

    /** price*quantity, carried rather than derived — it is summed constantly. */
    @Column(nullable = false)
    private long quoteQuantity;

    @Column(nullable = false)
    private UUID buyerOrderId;

    @Column(nullable = false)
    private UUID sellerOrderId;

    @Column(nullable = false)
    private Long buyerUserId;

    @Column(nullable = false)
    private Long sellerUserId;

    @Column(nullable = false)
    private boolean buyerIsMaker;

    /** A buyer pays in the base asset received; a seller in the quote asset. */
    @Column(nullable = false)
    private long buyerFee;

    private Long buyerFeeAssetId;

    @Column(nullable = false)
    private long sellerFee;

    private Long sellerFeeAssetId;

    /**
     * The engine's monotonic counter for this market. Two trades can share a microsecond;
     * this is what makes "which came first" answerable, and what a replay reproduces.
     */
    @Column(nullable = false)
    private long sequence;

    @Column(nullable = false)
    private Instant executedAt;

    // Set in Java, not left to the column's DB-side DEFAULT -- see AGENTS.md:
    // "Java-side defaults are what fire, not SQL defaults, Hibernate puts every column
    // in the INSERT." Also, empirically, what makes bulk-inserting many trades at once
    // batchable at all: @Generated(event = INSERT), reading the DB-computed value back
    // after each individual insert, defeats JDBC batching the same way an
    // IDENTITY-generated id does -- confirmed the same way SpotOrderFillLog's identical
    // fix was confirmed, via Hibernate's own batch statistics, not assumed.
    private Instant createdAt;

    @Transient
    private boolean isNew = false;

    public SpotTrade(UUID id, Long symbolId, long price, long quantity,
                     UUID buyerOrderId, UUID sellerOrderId,
                     Long buyerUserId, Long sellerUserId,
                     boolean buyerIsMaker, long sequence, Instant executedAt) {
        if (Objects.equals(buyerUserId, sellerUserId)) {
            throw new IllegalArgumentException("self-trade: user " + buyerUserId);
        }
        this.id = id;
        this.symbolId = symbolId;
        this.price = price;
        this.quantity = quantity;
        this.quoteQuantity = Money.multiply(price, quantity);
        this.buyerOrderId = buyerOrderId;
        this.sellerOrderId = sellerOrderId;
        this.buyerUserId = buyerUserId;
        this.sellerUserId = sellerUserId;
        this.buyerIsMaker = buyerIsMaker;
        this.sequence = sequence;
        this.executedAt = executedAt;
        this.createdAt = Instant.now();
        this.isNew = true;
    }

    public void chargeFees(long buyerFee, Long buyerFeeAssetId,
                           long sellerFee, Long sellerFeeAssetId) {
        this.buyerFee = buyerFee;
        this.buyerFeeAssetId = buyerFeeAssetId;
        this.sellerFee = sellerFee;
        this.sellerFeeAssetId = sellerFeeAssetId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotTrade other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
