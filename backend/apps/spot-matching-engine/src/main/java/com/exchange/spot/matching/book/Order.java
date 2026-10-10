package com.exchange.spot.matching.book;

import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import java.util.UUID;

/**
 * An order as the engine holds it — deliberately not the JPA entity of the same idea.
 * The engine has no database, and this carries only what matching needs.
 *
 * <p>Mutable in two respects: {@code remaining} shrinks as it fills, and an amend that
 * keeps queue priority shrinks {@code quantity} with it ({@link #reduceQuantityTo}). Every
 * other amend replaces the order, so everything else is final.
 *
 * <p>All amounts are scaled by 1e8.
 */
public final class Order {

    private final UUID id;
    private final long userId;
    private final Side side;
    private final OrderType type;
    private final TimeInForce timeInForce;

    /** Zero for a market order, which has no limit by definition. */
    private final long price;

    private long quantity;
    private long remaining;

    /**
     * The fee rates from this order's owner, stamped on at the moment it was placed.
     *
     * <p>Each fill charges the two sides their own rate, not one shared rate: a resting
     * order may have been sitting on the book for hours on a different fee tier -- maybe
     * even a rebate -- from whoever eventually trades against it. Charging both sides the
     * taker's rate would silently overcharge every maker.
     */
    private final int makerFeeBps;
    private final int takerFeeBps;

    /**
     * How this order behaves if it would match against its own owner's resting order.
     * Resolved at construction time -- UNSPECIFIED becomes EXPIRE_MAKER, the API's default --
     * so nothing downstream ever has to handle the unset case.
     */
    private final SelfTradePreventionMode selfTradePreventionMode;

    /**
     * True when {@link #quantity}/{@link #remaining} are a quote-asset budget ("spend
     * $100") rather than a base-asset quantity. Only meaningful for a MARKET order --
     * a resting order's price is already fixed, so its cost is just {@code price *
     * quantity} and there is nothing to walk the book for.
     */
    private final boolean quoteQuantity;

    public Order(UUID id, long userId, Side side, OrderType type, TimeInForce timeInForce,
                 long price, long quantity) {
        this(id, userId, side, type, timeInForce, price, quantity, 0, 0);
    }

    public Order(UUID id, long userId, Side side, OrderType type, TimeInForce timeInForce,
                 long price, long quantity, int makerFeeBps, int takerFeeBps) {
        this(id, userId, side, type, timeInForce, price, quantity, makerFeeBps, takerFeeBps,
                SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED);
    }

    public Order(UUID id, long userId, Side side, OrderType type, TimeInForce timeInForce,
                 long price, long quantity, int makerFeeBps, int takerFeeBps,
                 SelfTradePreventionMode selfTradePreventionMode) {
        this(id, userId, side, type, timeInForce, price, quantity, makerFeeBps, takerFeeBps,
                selfTradePreventionMode, false);
    }

    public Order(UUID id, long userId, Side side, OrderType type, TimeInForce timeInForce,
                 long price, long quantity, int makerFeeBps, int takerFeeBps,
                 SelfTradePreventionMode selfTradePreventionMode, boolean quoteQuantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (type == OrderType.ORDER_TYPE_LIMIT && price <= 0) {
            throw new IllegalArgumentException("a limit order needs a price");
        }
        this.id = id;
        this.userId = userId;
        this.side = side;
        this.type = type;
        // A market order fills whatever it can right now and never rests, whatever time in
        // force it arrived with -- so it has no meaningful choice to make here. Normalizing
        // to IOC, rather than trusting every caller to pass it consistently, is what keeps
        // OrderBook's FOK pre-check from ever seeing a market order that claims to be FOK.
        this.timeInForce = type == OrderType.ORDER_TYPE_MARKET
                ? TimeInForce.TIME_IN_FORCE_IOC
                : timeInForce;
        this.price = price;
        this.quantity = quantity;
        this.remaining = quantity;
        this.makerFeeBps = makerFeeBps;
        this.takerFeeBps = takerFeeBps;
        this.selfTradePreventionMode =
                selfTradePreventionMode == null
                        || selfTradePreventionMode
                                == SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_UNSPECIFIED
                        ? SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER
                        : selfTradePreventionMode;
        this.quoteQuantity = quoteQuantity;
    }

    /**
     * Rebuilds an order mid-life, with its already-filled portion intact.
     *
     * <p>Only for restoring a snapshot. The normal constructor starts an order untouched,
     * which is right for one that has just arrived and wrong for one that has been resting
     * and partially filling for hours.
     */
    public static Order restored(UUID id, long userId, Side side, OrderType type,
                                 TimeInForce timeInForce, long price, long quantity,
                                 long remaining, int makerFeeBps, int takerFeeBps,
                                 SelfTradePreventionMode selfTradePreventionMode) {
        if (remaining <= 0 || remaining > quantity) {
            throw new IllegalArgumentException(
                    "a resting order has 0 < remaining <= quantity, got " + remaining
                            + " of " + quantity);
        }
        Order order = new Order(id, userId, side, type, timeInForce, price, quantity,
                makerFeeBps, takerFeeBps, selfTradePreventionMode);
        order.remaining = remaining;
        return order;
    }

    public int makerFeeBps() {
        return makerFeeBps;
    }

    public int takerFeeBps() {
        return takerFeeBps;
    }

    public SelfTradePreventionMode selfTradePreventionMode() {
        return selfTradePreventionMode;
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

    public long price() {
        return price;
    }

    public long quantity() {
        return quantity;
    }

    public long remaining() {
        return remaining;
    }

    public long filled() {
        return quantity - remaining;
    }

    public boolean isFilled() {
        return remaining == 0;
    }

    boolean isMarket() {
        return type == OrderType.ORDER_TYPE_MARKET;
    }

    boolean isQuoteQuantity() {
        return quoteQuantity;
    }

    /**
     * Reduces the outstanding quantity. Rejects an overfill rather than clamping — filling
     * beyond what was ordered is quantity created from nothing, and it must surface here
     * rather than be absorbed and discovered in a reconciliation months later.
     */
    void reduce(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("fill must be positive, was " + amount);
        }
        if (amount > remaining) {
            throw new IllegalStateException(
                    "fill of " + amount + " exceeds remaining " + remaining + " on order " + id);
        }
        remaining -= amount;
    }

    /** An amend to a smaller total. The filled part stays filled, so only what is left shrinks. */
    void reduceQuantityTo(long newQuantity) {
        if (newQuantity <= filled() || newQuantity > quantity) {
            throw new IllegalArgumentException("new quantity " + newQuantity
                    + " must be above filled " + filled() + " and at most " + quantity);
        }
        remaining -= quantity - newQuantity;
        quantity = newQuantity;
    }

    /** True when this order sitting on the book could match an incoming one from the user. */
    boolean sameUserAs(Order other) {
        return userId == other.userId;
    }

    @Override
    public String toString() {
        return "Order[" + id + " " + side + " " + quantity + (quoteQuantity ? " quote" : "")
                + "@" + price + " remaining=" + remaining + "]";
    }
}
