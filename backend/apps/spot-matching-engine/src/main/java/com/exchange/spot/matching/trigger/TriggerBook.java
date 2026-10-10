package com.exchange.spot.matching.trigger;

import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.Side;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Conditional orders waiting for the price to reach them. One per market, held next to
 * {@code OrderBook} rather than inside it, because these can't be matched -- putting them
 * on the book would advertise liquidity that isn't really there.
 *
 * <p>Two sorted maps instead of one list, so checking which orders should fire is a
 * range lookup instead of scanning everything. When a trade happens at price P, every
 * order waiting for a fall to P-or-below fires in one step.
 *
 * <p>Like the order book: no clock, no randomness, not thread-safe. The engine feeds it
 * the last trade price, so replaying the command log rebuilds it exactly.
 */
public final class TriggerBook {

    /** Waiting for a fall. Keyed by stop price; a print at P fires every key >= P. */
    private final NavigableMap<Long, Map<UUID, PendingOrder>> fallTriggers = new TreeMap<>();

    /** Waiting for a rise. A print at P fires every key <= P. */
    private final NavigableMap<Long, Map<UUID, PendingOrder>> riseTriggers = new TreeMap<>();

    /** Every pending order by id, so cancel does not scan. */
    private final Map<UUID, PendingOrder> index = new HashMap<>();

    /**
     * Trailing orders that have started tracking -- either they reached their
     * activation price (moved here from the maps above by {@link #onPrice}), or they
     * had none and started immediately via {@link #activateTrailing}.
     *
     * <p>Deliberately not a sorted structure: every entry's effective stop price can
     * move on every single trade, so a range-query index buys nothing here that a full
     * scan does not already have to do anyway.
     */
    private final Map<UUID, TrailingOrder> activeTrailing = new HashMap<>();

    /**
     * Parks an order until its trigger is met.
     *
     * @throws IllegalArgumentException if the condition is already satisfied — the caller
     *                                  must check {@link #wouldTriggerImmediately} first
     *                                  and reject, because firing on arrival is never what
     *                                  the user meant
     */
    public void park(PendingOrder order) {
        if (isParked(order.id())) {
            throw new IllegalStateException("already parked: " + order.id());
        }
        mapFor(order.direction())
                .computeIfAbsent(order.stopPrice(), k -> new LinkedHashMap<>())
                .put(order.id(), order);
        index.put(order.id(), order);
    }

    /**
     * Starts a trailing order tracking immediately, using {@code currentPrice} as its
     * initial extreme -- for a trailing order with no activation price, which has
     * nothing to wait for. Same duplicate-id contract as {@link #park}.
     */
    public void activateTrailing(PendingOrder order, long currentPrice) {
        if (isParked(order.id())) {
            throw new IllegalStateException("already parked: " + order.id());
        }
        activeTrailing.put(order.id(), TrailingOrder.activate(order, currentPrice));
    }

    private boolean isParked(UUID orderId) {
        return index.containsKey(orderId) || activeTrailing.containsKey(orderId);
    }

    /**
     * Whether this order's condition is already met at the current price.
     *
     * <p>A market that has never traded has no price to judge against, so this returns
     * true and the caller rejects: parking it would mean firing on the first print, hours
     * later, at a price nobody was looking at.
     *
     * <p>A trailing order with no activation price is the one exception: there is
     * nothing for it to have "already met" -- it simply starts tracking from here --
     * so as long as the market has traded at all, this is never true for one.
     */
    public static boolean wouldTriggerImmediately(PendingOrder order, long lastTradePrice) {
        if (lastTradePrice <= 0) {
            return true;
        }
        if (order.isTrailing() && order.stopPrice() <= 0) {
            return false;
        }
        return order.direction().isMetBy(lastTradePrice, order.stopPrice());
    }

    /**
     * Fires everything this price reaches, removes it, and returns it in trigger-price
     * order — closest to the market first, which is the order the prints would have
     * reached them in had the move been gradual. Also ratchets and checks every
     * actively-trailing order, and promotes any activation-gated trailing order this
     * price newly reaches into active tracking rather than firing it outright.
     */
    public List<PendingOrder> onPrice(long price) {
        List<PendingOrder> fired = new ArrayList<>();

        // A trade can move an extreme even on a tick that fires nothing, so every
        // active trailing order is re-evaluated first, regardless of the fixed-price
        // maps below.
        List<UUID> triggered = new ArrayList<>();
        for (TrailingOrder trailing : activeTrailing.values()) {
            trailing.ratchet(price);
            if (trailing.hasTriggered(price)) {
                triggered.add(trailing.id());
            }
        }
        for (UUID id : triggered) {
            fired.add(activeTrailing.remove(id).toPendingOrder());
        }

        // Falling: the highest stop prices are reached first on the way down.
        NavigableMap<Long, Map<UUID, PendingOrder>> falling =
                fallTriggers.tailMap(price, true).descendingMap();
        drain(falling, fallTriggers, price, fired);

        // Rising: the lowest stop prices are reached first on the way up.
        drain(riseTriggers.headMap(price, true), riseTriggers, price, fired);

        return fired;
    }

    private void drain(NavigableMap<Long, Map<UUID, PendingOrder>> reached,
                       NavigableMap<Long, Map<UUID, PendingOrder>> source,
                       long price, List<PendingOrder> fired) {
        List<Long> emptied = new ArrayList<>();
        for (Map.Entry<Long, Map<UUID, PendingOrder>> entry : reached.entrySet()) {
            for (PendingOrder order : entry.getValue().values()) {
                index.remove(order.id());
                if (order.isTrailing()) {
                    // Reached its ACTIVATION price -- starts tracking now, using this
                    // print as its first extreme, rather than firing.
                    activeTrailing.put(order.id(), TrailingOrder.activate(order, price));
                } else {
                    fired.add(order);
                }
            }
            emptied.add(entry.getKey());
        }
        emptied.forEach(source::remove);
    }

    /**
     * Changes a parked order's stop price, limit price or size; null keeps a value.
     *
     * <p>A trailing order takes a new size only, tracking or not: its trigger is derived
     * from the market, and nowhere lets that be edited in place. A
     * plain order's new stop price gets the same test as a new order's, so an amend can
     * never be a way to fire one on the spot. Every refusal leaves the order untouched.
     */
    public TriggerAmendResult amend(UUID orderId, Long newStopPrice, Long newLimitPrice,
                                    Long newQuantity, long lastTradePrice) {
        TrailingOrder trailing = activeTrailing.get(orderId);
        PendingOrder before = trailing != null ? trailing.toPendingOrder() : index.get(orderId);
        if (before == null) {
            return TriggerAmendResult.NOT_PARKED;
        }
        boolean isLimit = before.limitPrice() > 0;
        if ((before.isTrailing() && (newStopPrice != null || newLimitPrice != null))
                || (newLimitPrice != null && !isLimit)) {
            return TriggerAmendResult.rejected(
                    AmendRejectReason.AMEND_REJECT_REASON_FIELD_NOT_AMENDABLE, before);
        }
        PendingOrder after = new PendingOrder(before.id(), before.userId(), before.side(),
                before.type(), before.timeInForce(),
                newStopPrice == null ? before.stopPrice() : newStopPrice,
                newLimitPrice == null ? before.limitPrice() : newLimitPrice,
                newQuantity == null ? before.quantity() : newQuantity,
                before.makerFeeBps(), before.takerFeeBps(), before.clientOrderId(),
                before.selfTradePreventionMode(), before.trailingDeltaBps(),
                before.isQuoteQuantity());
        if (newStopPrice != null && wouldTriggerImmediately(after, lastTradePrice)) {
            return TriggerAmendResult.rejected(
                    AmendRejectReason.AMEND_REJECT_REASON_WOULD_TRIGGER_IMMEDIATELY, before);
        }
        if (trailing != null) {
            trailing.amendQuantity(after.quantity());
        } else {
            cancel(orderId);
            park(after);
        }
        return new TriggerAmendResult(null, before, after);
    }

    /** Returns the removed order, or null if it was never parked. */
    public PendingOrder cancel(UUID orderId) {
        PendingOrder order = index.remove(orderId);
        if (order != null) {
            NavigableMap<Long, Map<UUID, PendingOrder>> side = mapFor(order.direction());
            Map<UUID, PendingOrder> atPrice = side.get(order.stopPrice());
            if (atPrice != null) {
                atPrice.remove(orderId);
                if (atPrice.isEmpty()) {
                    side.remove(order.stopPrice());
                }
            }
            return order;
        }
        TrailingOrder trailing = activeTrailing.remove(orderId);
        return trailing == null ? null : trailing.toPendingOrder();
    }

    public List<PendingOrder> cancelAllFor(long userId) {
        return cancelAllFor(userId, null, null);
    }

    /**
     * One user's pending orders matching every filter given, removed; null means any. The
     * type filtered on is the conditional type the order was placed as -- STOP_LOSS and the
     * rest -- not the LIMIT or MARKET order it would have become had it fired.
     *
     * <p>No owner index here, unlike the book: this holds only conditional orders, so the
     * scan is short, and an order moves between {@code index} and {@code activeTrailing}
     * as it activates -- a third view would have to follow it on every promotion.
     */
    public List<PendingOrder> cancelAllFor(long userId, Side side, OrderType type) {
        List<UUID> ids = new ArrayList<>();
        index.values().stream()
                .filter(o -> o.userId() == userId)
                .filter(o -> side == null || o.side() == side)
                .filter(o -> type == null || o.type() == type)
                .map(PendingOrder::id)
                .forEach(ids::add);
        activeTrailing.values().stream()
                .filter(o -> o.userId() == userId)
                .filter(o -> side == null || o.side() == side)
                .filter(o -> type == null || o.type() == type)
                .map(TrailingOrder::id)
                .forEach(ids::add);

        List<PendingOrder> removed = new ArrayList<>();
        for (UUID id : ids) {
            PendingOrder order = cancel(id);
            if (order != null) {
                removed.add(order);
            }
        }
        return removed;
    }

    /** Every plain or activation-gated pending order. Unlike the book there is no
     *  queue to preserve — a trigger fires on price, not on position. */
    public List<PendingOrder> snapshotPending() {
        return List.copyOf(index.values());
    }

    /** Every actively-trailing order, extreme and all. See {@link #snapshotPending}
     *  for everything still waiting on a fixed price. */
    public List<TrailingOrder> snapshotTrailing() {
        return List.copyOf(activeTrailing.values());
    }

    /** Rebuilds from {@link #snapshotPending}. Must be empty. */
    public void restore(List<PendingOrder> orders) {
        if (!index.isEmpty()) {
            throw new IllegalStateException("restoring into a non-empty trigger book");
        }
        orders.forEach(this::park);
    }

    /** Rebuilds from {@link #snapshotTrailing}, preserving each order's tracked
     *  extreme exactly -- restarting it from the current market price instead would
     *  silently loosen its stop. Must be empty. */
    public void restoreTrailing(List<TrailingOrder> orders) {
        if (!activeTrailing.isEmpty()) {
            throw new IllegalStateException("restoring into a non-empty trigger book");
        }
        orders.forEach(order -> activeTrailing.put(order.id(), order));
    }

    public PendingOrder find(UUID orderId) {
        return index.get(orderId);
    }

    /** Parked or tracking, and this user's; null otherwise. */
    public PendingOrder find(UUID orderId, long userId) {
        TrailingOrder trailing = activeTrailing.get(orderId);
        PendingOrder order = trailing != null ? trailing.toPendingOrder() : index.get(orderId);
        return order != null && order.userId() == userId ? order : null;
    }

    public int pendingCount() {
        return index.size() + activeTrailing.size();
    }

    public boolean isEmpty() {
        return index.isEmpty() && activeTrailing.isEmpty();
    }

    private NavigableMap<Long, Map<UUID, PendingOrder>> mapFor(TriggerDirection direction) {
        return direction == TriggerDirection.FALL ? fallTriggers : riseTriggers;
    }
}
