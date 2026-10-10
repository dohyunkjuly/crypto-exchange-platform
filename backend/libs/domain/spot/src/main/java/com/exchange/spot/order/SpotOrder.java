package com.exchange.spot.order;

import com.exchange.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
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
 * An order, from acceptance to its terminal state.
 *
 * <p>The id is assigned by the application, not the database: the API returns it in the
 * 202 response before anything is written, and the engine references the same id while
 * matching in RAM. Generate it as a ULID so it stays time-ordered and the primary key does
 * not fragment.
 *
 * <p>{@code makerFeeBps} and {@code takerFeeBps} are stamped at acceptance. Fills charge
 * the stamped rate and never read the fee tables — which keeps fee resolution to once per
 * order, stops a partially filled order changing rate halfway through, and leaves the
 * charge explainable years later.
 *
 * <p>Implements {@link Persistable} because an assigned id would otherwise make
 * {@code save()} issue a SELECT before every INSERT to decide whether the row already
 * exists. {@code isNew} tracks it instead.
 */
@Entity
@Table(name = "spot_orders")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SpotOrder implements Persistable<UUID> {

    @Id
    @Setter(AccessLevel.NONE)
    private UUID id;

    /** Client-supplied, so a retried submit returns the original rather than placing two. */
    @Column(length = 64)
    @Setter(AccessLevel.NONE)
    private String clientOrderId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long userId;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long symbolId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    @Setter(AccessLevel.NONE)
    private Side side;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Setter(AccessLevel.NONE)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    @Setter(AccessLevel.NONE)
    private TimeInForce timeInForce;

    /** All scaled by 1e8. Null on a market order, which has no limit by definition. */
    @Setter(AccessLevel.NONE)
    private Long price;

    @Setter(AccessLevel.NONE)
    private Long stopPrice;

    /** Null for a plain (non-trailing) conditional order, and for every non-conditional
     *  type. When set, {@link #stopPrice} is an optional ACTIVATION price rather than a
     *  fixed trigger -- absent, tracking starts immediately at acceptance. */
    @Setter(AccessLevel.NONE)
    private Integer trailingDeltaBps;

    /** Zero when {@link #quoteOrderQty} is set instead -- the base amount is not
     *  knowable until the order fills. */
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long quantity;

    /** "Buy $100 of BTC" — a market buy (or a plain STOP_LOSS/TAKE_PROFIT, which
     *  becomes one when it fires) sized in the quote asset instead of the base. */
    @Setter(AccessLevel.NONE)
    private Long quoteOrderQty;

    @Column(nullable = false)
    private long filledQuantity;

    /** Sum of price*qty over fills, so average price is a division not an aggregate. */
    @Column(nullable = false)
    private long cumulativeQuoteQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status = OrderStatus.NEW;

    @Column(length = 64)
    private String rejectReason;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int makerFeeBps;

    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private int takerFeeBps;

    /** Unused. */
    private Instant expiresAt;

    /** An AmendOrder is on its way to the engine. Set by the API, cleared by settlement,
     *  and a second amend is refused meanwhile: see V4__spot.sql for what goes wrong otherwise. */
    @Column(nullable = false)
    private boolean amendPending = false;

    /** Two concurrent fills against this row would otherwise be a lost update -- see
     *  {@code Balance}'s own field of the same name, added for the identical reason. */
    @Version
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private long version;

    @Generated(event = EventType.INSERT)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Setter(AccessLevel.NONE)
    private Instant updatedAt;

    @Transient
    private boolean isNew = false;

    public SpotOrder(UUID id, Long userId, Long symbolId, Side side, OrderType orderType,
                     TimeInForce timeInForce, Long price, Long stopPrice, long quantity,
                     Long quoteOrderQty, int makerFeeBps, int takerFeeBps,
                     String clientOrderId, Integer trailingDeltaBps) {
        this.id = id;
        this.userId = userId;
        this.symbolId = symbolId;
        this.side = side;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.price = price;
        this.stopPrice = stopPrice;
        this.quantity = quantity;
        this.quoteOrderQty = quoteOrderQty;
        this.makerFeeBps = makerFeeBps;
        this.takerFeeBps = takerFeeBps;
        this.clientOrderId = clientOrderId;
        this.trailingDeltaBps = trailingDeltaBps;
        this.isNew = true;
    }

    public long remainingQuantity() {
        return quantity - filledQuantity;
    }

    /**
     * The engine accepted an amend: null keeps a field. {@code newQuantity} is the new total,
     * or the new quote budget on a {@link #quoteOrderQty} order, whose {@link #quantity}
     * stays zero.
     */
    public void applyAmend(Long newPrice, Long newStopPrice, Long newQuantity) {
        if (newPrice != null) {
            price = newPrice;
        }
        if (newStopPrice != null) {
            stopPrice = newStopPrice;
        }
        if (newQuantity != null) {
            if (quoteOrderQty != null) {
                quoteOrderQty = newQuantity;
            } else {
                quantity = newQuantity;
            }
        }
        amendPending = false;
    }

    /** Weighted average fill price, scaled by 1e8. Zero before the first fill. */
    public long averageFillPrice() {
        return filledQuantity == 0 ? 0 : Money.divide(cumulativeQuoteQuantity, filledQuantity);
    }

    /**
     * Applies a fill. Rejects overfill rather than clamping — a fill beyond what was
     * ordered is money created from nothing, and it must surface, not be absorbed.
     *
     * <p>{@link #quantity} is not a valid overfill bound for a {@link #quoteOrderQty}
     * order — it stays zero for the order's whole life (see its own javadoc), so the
     * plain-quantity check below would reject every single fill of one. The bound that
     * actually applies there is the remaining quote budget instead.
     *
     * <p>The status this leaves behind for a {@code quoteOrderQty} order is not
     * authoritative — a market-firing order almost never exhausts its budget to the
     * last unit (the engine's own dust guard stops one unit short), so equality against
     * {@link #quoteOrderQty} is not a reliable "done" signal. The caller applying an
     * event from the engine already has the real answer (the event's own status field)
     * and is expected to set it directly afterward; this method's job is only to keep
     * the running totals correct and refuse a genuine overfill.
     */
    public void applyFill(long fillQuantity, long fillQuoteQuantity) {
        if (fillQuantity <= 0) {
            throw new IllegalArgumentException("fill quantity must be positive");
        }
        if (quoteOrderQty != null) {
            long remainingQuoteBudget = quoteOrderQty - cumulativeQuoteQuantity;
            if (fillQuoteQuantity > remainingQuoteBudget) {
                throw new IllegalStateException(
                        "fill of " + fillQuoteQuantity + " exceeds remaining quote budget "
                                + remainingQuoteBudget + " on order " + id);
            }
        } else if (fillQuantity > remainingQuantity()) {
            throw new IllegalStateException(
                    "fill of " + fillQuantity + " exceeds remaining " + remainingQuantity()
                            + " on order " + id);
        }
        filledQuantity += fillQuantity;
        cumulativeQuoteQuantity += fillQuoteQuantity;
        status = filledQuantity == quantity ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** Called by Spring Data after the insert so a later save() becomes an update. */
    public void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SpotOrder other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
