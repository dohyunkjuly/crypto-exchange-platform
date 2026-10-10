package com.exchange.spot.matching.trigger;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import java.util.UUID;

/**
 * A conditional order that is actively trailing the market, waiting for a reversal of
 * {@code trailingDeltaBps} from the best price seen since it started tracking.
 *
 * <p>Deliberately not a record, the same way {@link com.exchange.spot.matching.book.Order}
 * isn't: everything here is final except {@link #extremePrice}, which legitimately
 * moves -- ratcheting favorably on every trade is the entire point of a trailing order.
 *
 * <p>Lives beside {@link PendingOrder} rather than replacing it: a trailing order still
 * spends part of its life as a plain {@code PendingOrder} parked at a fixed activation
 * price (or with none at all), and only becomes one of these once it actually starts
 * tracking. See {@link TriggerBook}.
 */
public final class TrailingOrder {

    private final UUID id;
    private final long userId;
    private final Side side;
    private final OrderType type;
    private final TimeInForce timeInForce;
    /** Zero for the plain stop types, which become market orders when they fire. */
    private final long limitPrice;
    /** The only term an amend may change on a trailing order. */
    private long quantity;
    private final int makerFeeBps;
    private final int takerFeeBps;
    private final String clientOrderId;
    private final SelfTradePreventionMode selfTradePreventionMode;
    private final int trailingDeltaBps;
    private final boolean quoteQuantity;
    private final TriggerDirection direction;

    /** The best price seen since tracking started -- a high-water mark for a FALL
     *  order, a low-water mark for a RISE order. Mutable, like {@link #quantity}. */
    private long extremePrice;

    public TrailingOrder(UUID id, long userId, Side side, OrderType type,
                         TimeInForce timeInForce, long limitPrice, long quantity,
                         int makerFeeBps, int takerFeeBps, String clientOrderId,
                         SelfTradePreventionMode selfTradePreventionMode,
                         int trailingDeltaBps, boolean quoteQuantity, long extremePrice) {
        this.id = id;
        this.userId = userId;
        this.side = side;
        this.type = type;
        this.timeInForce = timeInForce;
        this.limitPrice = limitPrice;
        this.quantity = quantity;
        this.makerFeeBps = makerFeeBps;
        this.takerFeeBps = takerFeeBps;
        this.clientOrderId = clientOrderId;
        this.selfTradePreventionMode = selfTradePreventionMode;
        this.trailingDeltaBps = trailingDeltaBps;
        this.quoteQuantity = quoteQuantity;
        this.direction = TriggerDirection.of(type, side);
        this.extremePrice = extremePrice;
    }

    /** From a {@link PendingOrder} that just started tracking -- either it reached its
     *  activation price, or it never had one and {@code initialPrice} is simply the
     *  market price at the moment it was placed. */
    static TrailingOrder activate(PendingOrder order, long initialPrice) {
        return new TrailingOrder(order.id(), order.userId(), order.side(), order.type(),
                order.timeInForce(), order.limitPrice(), order.quantity(),
                order.makerFeeBps(), order.takerFeeBps(), order.clientOrderId(),
                order.selfTradePreventionMode(), order.trailingDeltaBps(),
                order.isQuoteQuantity(), initialPrice);
    }

    public UUID id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public Side side() {
        return side;
    }

    public OrderType type() {
        return type;
    }

    public TimeInForce timeInForce() {
        return timeInForce;
    }

    public long limitPrice() {
        return limitPrice;
    }

    public long quantity() {
        return quantity;
    }

    public int makerFeeBps() {
        return makerFeeBps;
    }

    public int takerFeeBps() {
        return takerFeeBps;
    }

    public String clientOrderId() {
        return clientOrderId;
    }

    public SelfTradePreventionMode selfTradePreventionMode() {
        return selfTradePreventionMode;
    }

    public int trailingDeltaBps() {
        return trailingDeltaBps;
    }

    public boolean isQuoteQuantity() {
        return quoteQuantity;
    }

    public long extremePrice() {
        return extremePrice;
    }

    void amendQuantity(long newQuantity) {
        quantity = newQuantity;
    }

    /** Moves the tracked extreme, but only in the favorable direction. Never reverses
     *  -- that would mean the stop loosening on its own, which is the one thing a
     *  trailing stop must never do. */
    void ratchet(long price) {
        extremePrice = direction == TriggerDirection.FALL
                ? Math.max(extremePrice, price)
                : Math.min(extremePrice, price);
    }

    /** The price this order fires at right now. Moves every time {@link #ratchet}
     *  moves the extreme -- this is the whole mechanism, not a cached value. */
    public long effectiveStopPrice() {
        long offset = Money.applyBps(extremePrice, trailingDeltaBps);
        return direction == TriggerDirection.FALL ? extremePrice - offset : extremePrice + offset;
    }

    boolean hasTriggered(long price) {
        return direction.isMetBy(price, effectiveStopPrice());
    }

    /** Converts back to a {@link PendingOrder} the instant this fires, so every other
     *  call site keeps working against one uniform type without ever needing to know a
     *  trailing order was involved. {@code stopPrice} carries the effective stop this
     *  fired at, purely for observability -- {@link PendingOrder#toActiveOrder()}
     *  never reads it. */
    PendingOrder toPendingOrder() {
        return new PendingOrder(id, userId, side, type, timeInForce, effectiveStopPrice(),
                limitPrice, quantity, makerFeeBps, takerFeeBps, clientOrderId,
                selfTradePreventionMode, trailingDeltaBps, quoteQuantity);
    }
}
