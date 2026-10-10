package com.exchange.copy.leadorder;

import com.exchange.spot.order.Side;
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
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.springframework.data.domain.Persistable;

/**
 * A lead account's order and the fraction it used: of the available USDT for a buy, of the
 * holding for a sell (docs/reference/copy-trading.md). Written in the placing transaction --
 * by the time the order closes the balance has moved, and the fraction cannot be worked out.
 * Keyed by the order, so the row is found from an event with no second lookup.
 */
@Entity
@Table(name = "copy_lead_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CopyLeadOrder implements Persistable<UUID> {

    /** All of it: a fraction is scaled by 1e8, like money. */
    public static final long WHOLE = 100_000_000L;

    @Id
    private UUID orderId;

    @Column(nullable = false)
    private UUID leaderId;

    @Column(nullable = false)
    private Long symbolId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Side side;

    /** Above zero, at most {@link #WHOLE}. An amend changes it. */
    @Column(nullable = false)
    @Setter
    private long fraction;

    /**
     * What {@link #fraction} was a share of at placement: the cost in the quote asset for a buy,
     * the quantity for a sell. A part-filled order is copied in that part, and an amend
     * rescales the fraction by how this moved.
     */
    @Column(nullable = false)
    @Setter
    private long amount;

    /** The quantity ordered, in the base asset; zero for an order sized in the quote asset. An amend changes it. */
    @Column(nullable = false)
    @Setter
    private long quantity;

    /** What has filled so far, added to as trades arrive. */
    @Column(nullable = false)
    private long filledQuantity;

    @Column(nullable = false)
    private long filledQuoteQuantity;

    /** The last trade counted. Only a later one is added, so a redelivered trade is not counted twice. */
    @Column(nullable = false)
    private long lastSequence;

    /** When the copier handled its closing; null while the order is still open. */
    @Setter
    private Instant copiedAt;

    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    @Transient
    @Getter(AccessLevel.NONE)
    private boolean isNew = false;

    /** @param quantity zero for an order sized in the quote asset */
    public CopyLeadOrder(UUID orderId, UUID leaderId, Long symbolId, Side side, long fraction, long amount,
                         long quantity) {
        this.orderId = orderId;
        this.leaderId = leaderId;
        this.symbolId = symbolId;
        this.side = side;
        this.fraction = fraction;
        this.amount = amount;
        this.quantity = quantity;
        this.isNew = true;
    }

    /**
     * Whether what has filled covers what was ordered. This, not a trade's status, is how a
     * fill is known to be the last: the engine stamps every trade of a taker with the order's
     * final status, so the first of five says FILLED too.
     */
    public boolean isFilled() {
        return quantity > 0 ? filledQuantity >= quantity : filledQuoteQuantity >= amount;
    }

    /** @return false for a trade already counted */
    public boolean addFill(long sequence, long quantity, long quoteQuantity) {
        if (sequence <= lastSequence) {
            return false;
        }
        lastSequence = sequence;
        filledQuantity += quantity;
        filledQuoteQuantity += quoteQuantity;
        return true;
    }

    /** How much of {@link #amount} has filled, in its own unit. */
    public long filledAmount() {
        return side == Side.BUY ? filledQuoteQuantity : filledQuantity;
    }

    /** Saved once as new; the same object saved again is an update. */
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public UUID getId() {
        return orderId;
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
        if (!(o instanceof CopyLeadOrder other)) {
            return false;
        }
        return orderId != null && orderId.equals(other.orderId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
