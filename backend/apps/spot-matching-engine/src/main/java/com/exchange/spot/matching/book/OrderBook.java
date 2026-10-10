package com.exchange.spot.matching.book;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.AmendRejectReason;
import com.exchange.contracts.spot.v1.OrderType;
import com.exchange.contracts.spot.v1.RejectReason;
import com.exchange.contracts.spot.v1.SelfTradePreventionMode;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TimeInForce;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One market's order book. Price-time priority, in memory, single-threaded.
 *
 * <p>Not thread safe, on purpose. Orders must be matched in the exact order they arrived,
 * and having one Kafka partition go to one consumer thread already guarantees that. A lock
 * here would let two threads interleave and turn "who was first" into a coin flip — on an
 * exchange, that's a fairness bug, not just a performance one.
 *
 * <p>No clock, no randomness. Feed the same orders into an empty book and you always get
 * the same book back, which is what lets a crash recover by simply replaying the log.
 *
 * <p><b>Self-trade prevention</b> is always on, and per-order: {@link Order#selfTradePreventionMode()}
 * on the incoming (taker) order decides what happens when it would match its own owner's
 * resting order. No mode lets it trade. Every mode cancels rather than rejects — the new order is acting on the book it can actually see, so rejecting
 * it over a stale order the user may have forgotten about would be surprising. Cancelling
 * that stale order (or the incoming one's own remainder, or both) instead is not.
 */
public final class OrderBook {

    private final String symbol;

    /** Descending: the best bid — the highest price a buyer will pay — is first. */
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Comparator.reverseOrder());

    /** Ascending: the best ask — the lowest price a seller will accept — is first. */
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();

    /** Every resting order by id, so cancel does not have to scan the book. */
    private final Map<UUID, Order> index = new HashMap<>();

    /**
     * The same orders grouped by owner, so a cancel-all walks one user's orders instead of
     * the whole book. Insertion-ordered on purpose: iteration order decides the order the
     * cancel events come out in, and a LinkedHashSet gives placement order on every run,
     * where a HashSet of identity-hashed orders would differ between JVMs and break replay.
     */
    private final Map<Long, Set<Order>> byUser = new HashMap<>();

    public OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    // ---------------------------------------------------------------- submit

    public MatchResult submit(Order taker) {
        if (index.containsKey(taker.id())) {
            return MatchResult.rejected(RejectReason.REJECT_REASON_DUPLICATE_ORDER_ID);
        }

        // A post-only order must never take. Checked before anything mutates, so a
        // rejection leaves the book exactly as it was.
        if (taker.type() == OrderType.ORDER_TYPE_LIMIT_MAKER && wouldCross(taker)) {
            return MatchResult.rejected(RejectReason.REJECT_REASON_WOULD_TAKE);
        }

        // Fill-or-kill has to know the answer before it commits to anything, so it walks
        // the book once without mutating. Any partial fill would violate the contract.
        if (taker.timeInForce() == TimeInForce.TIME_IN_FORCE_FOK) {
            RejectReason unfillable = fokRejection(taker);
            if (unfillable != null) {
                return MatchResult.rejected(unfillable);
            }
        }

        List<Fill> fills = new ArrayList<>();
        List<Order> expiredMakers = new ArrayList<>();
        boolean takerExpiredBySelfTrade = match(taker, fills, expiredMakers);

        long remaining = taker.remaining();
        if (remaining == 0) {
            return new MatchResult(fills, 0, 0, null, expiredMakers, false);
        }

        // A taker cut short by its own self-trade prevention never rests, whatever its
        // time in force says -- there is nothing left it is willing to do with the rest.
        if (takerExpiredBySelfTrade) {
            return new MatchResult(fills, 0, remaining, null, expiredMakers, true);
        }

        // What happens to a remainder is entirely a function of the order's terms.
        boolean rests = !taker.isMarket()
                && taker.timeInForce() != TimeInForce.TIME_IN_FORCE_IOC
                && taker.timeInForce() != TimeInForce.TIME_IN_FORCE_FOK;

        if (rests) {
            rest(taker);
            return new MatchResult(fills, remaining, 0, null, expiredMakers, false);
        }
        return new MatchResult(fills, 0, remaining, null, expiredMakers, false);
    }

    /** @return true if self-trade prevention cut the taker itself short (EXPIRE_TAKER or
     *          EXPIRE_BOTH), meaning there is nothing left for it to do but stop. */
    private boolean match(Order taker, List<Fill> fills, List<Order> expiredMakers) {
        NavigableMap<Long, PriceLevel> opposite = taker.side() == Side.SIDE_BUY ? asks : bids;
        SelfTradePreventionMode stp = taker.selfTradePreventionMode();

        while (taker.remaining() > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, PriceLevel> best = opposite.firstEntry();
            if (!crosses(taker, best.getKey())) {
                break;
            }

            PriceLevel level = best.getValue();
            Iterator<Order> makers = level.orders().iterator();

            while (makers.hasNext() && taker.remaining() > 0) {
                Order maker = makers.next();

                if (maker.sameUserAs(taker)) {
                    if (stp != SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_TAKER) {
                        // Withdraw the stale quote and carry on down the book.
                        makers.remove();
                        level.reduceTotal(maker.remaining());
                        untrack(maker);
                        expiredMakers.add(maker);
                    }
                    if (stp != SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER) {
                        // EXPIRE_TAKER or EXPIRE_BOTH: the taker itself gives way here too,
                        // so there is nothing left for it to match against.
                        if (level.isEmpty()) {
                            opposite.remove(best.getKey());
                        }
                        return true;
                    }
                    continue;
                }

                long quantity = taker.isQuoteQuantity()
                        ? Math.min(maker.remaining(), Money.divide(taker.remaining(), level.price()))
                        : Math.min(taker.remaining(), maker.remaining());
                if (quantity == 0) {
                    // The remaining quote budget can't afford even one unit at the best
                    // available price -- and every other level is worse -- so there is
                    // nothing left this taker can do. Whatever's left becomes dust,
                    // cancelled the same way any other unfillable remainder is.
                    return false;
                }
                taker.reduce(taker.isQuoteQuantity() ? Money.multiply(level.price(), quantity) : quantity);
                maker.reduce(quantity);
                level.reduceTotal(quantity);

                // level.price(), not taker.price(). The maker was here first, so the
                // price improvement is the taker's.
                fills.add(new Fill(maker.id(), maker.userId(),
                        level.price(), quantity, maker.remaining(), maker.makerFeeBps()));

                if (maker.isFilled()) {
                    makers.remove();
                    untrack(maker);
                }
            }

            if (level.isEmpty()) {
                opposite.remove(best.getKey());
            }
        }
        return false;
    }

    private void rest(Order order) {
        NavigableMap<Long, PriceLevel> side =
                order.side() == Side.SIDE_BUY ? bids : asks;
        side.computeIfAbsent(order.price(), PriceLevel::new).add(order);
        track(order);
    }

    // ----------------------------------------------------------------- amend

    /**
     * Changes a resting order's price, total quantity or both; null keeps a value.
     *
     * <p>A smaller size at the same price is done in place and keeps the order's place in
     * the queue. Anything else gives the order new terms others have not had a chance to
     * trade against, so it leaves the queue and goes back through {@link #submit} exactly as
     * a new order would: a price that now crosses trades as a taker, under the order's own
     * self-trade prevention mode. Every refusal is decided before anything mutates.
     */
    public AmendResult amend(UUID orderId, Long newPrice, Long newQuantity) {
        Order order = index.get(orderId);
        if (order == null) {
            return AmendResult.rejected(AmendRejectReason.AMEND_REJECT_REASON_ORDER_NOT_FOUND);
        }
        long price = newPrice == null ? order.price() : newPrice;
        long quantity = newQuantity == null ? order.quantity() : newQuantity;
        if (quantity <= order.filled()) {
            return AmendResult.rejected(
                    AmendRejectReason.AMEND_REJECT_REASON_QUANTITY_NOT_ABOVE_FILLED);
        }
        long oldPrice = order.price();
        long oldRemaining = order.remaining();
        long newRemaining = quantity - order.filled();

        if (price == oldPrice && newRemaining <= oldRemaining) {
            order.reduceQuantityTo(quantity);
            levelOf(order).reduceTotal(oldRemaining - newRemaining);
            return new AmendResult(null, order, oldPrice, oldRemaining, newRemaining, true, null);
        }

        Order replacement = Order.restored(order.id(), order.userId(), order.side(), order.type(),
                order.timeInForce(), price, quantity, newRemaining, order.makerFeeBps(),
                order.takerFeeBps(), order.selfTradePreventionMode());
        if (replacement.type() == OrderType.ORDER_TYPE_LIMIT_MAKER && wouldCross(replacement)) {
            return AmendResult.rejected(AmendRejectReason.AMEND_REJECT_REASON_WOULD_TAKE);
        }
        cancel(orderId);
        MatchResult match = submit(replacement);
        return new AmendResult(null, replacement, oldPrice, oldRemaining, newRemaining, false, match);
    }

    private PriceLevel levelOf(Order order) {
        return (order.side() == Side.SIDE_BUY ? bids : asks).get(order.price());
    }

    // ---------------------------------------------------------------- cancel

    /** Returns the removed order, or null if it was never here — already filled, or a
     *  cancel racing a fill, which is normal and not an error. */
    public Order cancel(UUID orderId) {
        Order order = index.get(orderId);
        if (order == null) {
            return null;
        }
        untrack(order);
        NavigableMap<Long, PriceLevel> side =
                order.side() == Side.SIDE_BUY ? bids : asks;
        PriceLevel level = side.get(order.price());
        if (level != null) {
            level.remove(orderId);
            if (level.isEmpty()) {
                side.remove(order.price());
            }
        }
        return order;
    }

    /** Every resting order for one user, for a cancel-all. */
    public List<Order> cancelAllFor(long userId) {
        return cancelAllFor(userId, null, null);
    }

    /**
     * One user's resting orders matching every filter given, removed; null means any.
     *
     * <p>Neither filter narrows much on its own -- a side halves the set, a type divides it
     * by a handful -- so they are a scan of {@link #byUser}, not indexes of their own. What
     * makes this cheap is the owner lookup.
     */
    public List<Order> cancelAllFor(long userId, Side side, OrderType type) {
        Set<Order> mine = byUser.get(userId);
        if (mine == null) {
            return List.of();
        }
        // Settled before the first cancel: each one removes from the very set being walked.
        List<Order> matching = mine.stream()
                .filter(o -> side == null || o.side() == side)
                .filter(o -> type == null || o.type() == type)
                .toList();
        for (Order order : matching) {
            cancel(order.id());
        }
        return matching;
    }

    /** Resting means present in both views; these two are the only places either changes. */
    private void track(Order order) {
        index.put(order.id(), order);
        byUser.computeIfAbsent(order.userId(), owner -> new LinkedHashSet<>()).add(order);
    }

    private void untrack(Order order) {
        index.remove(order.id());
        Set<Order> mine = byUser.get(order.userId());
        if (mine != null) {
            mine.remove(order);
            if (mine.isEmpty()) {
                byUser.remove(order.userId());
            }
        }
    }

    // ------------------------------------------------------------ inspection

    public Long bestBid() {
        return bids.isEmpty() ? null : bids.firstKey();
    }

    public Long bestAsk() {
        return asks.isEmpty() ? null : asks.firstKey();
    }

    /** Best ask minus best bid, or null while either side is empty. */
    public Long spread() {
        Long bid = bestBid();
        Long ask = bestAsk();
        return bid == null || ask == null ? null : ask - bid;
    }

    /** Aggregated depth, best price first — this is L2. Each entry is {price, quantity}. */
    public List<long[]> bidDepth(int levels) {
        return depth(bids, levels);
    }

    public List<long[]> askDepth(int levels) {
        return depth(asks, levels);
    }

    private static List<long[]> depth(NavigableMap<Long, PriceLevel> side, int levels) {
        List<long[]> out = new ArrayList<>(Math.min(levels, side.size()));
        for (PriceLevel level : side.values()) {
            if (out.size() == levels) {
                break;
            }
            out.add(new long[] {level.price(), level.totalQuantity()});
        }
        return out;
    }

    /**
     * Every resting order, in book order: best price first, and within a price the order
     * they arrived in.
     *
     * <p>That ordering IS the time priority. {@link #restore} re-inserts in the same
     * sequence, so a snapshot round trip leaves every queue position untouched — shuffle
     * the list and resting orders silently change places.
     */
    public List<Order> snapshotOrders() {
        List<Order> all = new ArrayList<>(index.size());
        bids.values().forEach(level -> all.addAll(level.orders()));
        asks.values().forEach(level -> all.addAll(level.orders()));
        return all;
    }

    /** Rebuilds from {@link #snapshotOrders}. The book must be empty. */
    public void restore(List<Order> orders) {
        if (!index.isEmpty()) {
            throw new IllegalStateException("restoring into a book that already has orders");
        }
        orders.forEach(this::rest);
    }

    public Order find(UUID orderId) {
        return index.get(orderId);
    }

    public int restingOrderCount() {
        return index.size();
    }

    public boolean isEmpty() {
        return index.isEmpty();
    }

    // --------------------------------------------------------------- helpers

    /** Would this order cross against a resting price? Market orders always would. */
    private static boolean crosses(Order taker, long restingPrice) {
        if (taker.isMarket()) {
            return true;
        }
        return taker.side() == Side.SIDE_BUY
                ? taker.price() >= restingPrice
                : taker.price() <= restingPrice;
    }

    private boolean wouldCross(Order order) {
        Long best = order.side() == Side.SIDE_BUY ? bestAsk() : bestBid();
        return best != null && crosses(order, best);
    }

    /**
     * Why this FOK order cannot fill in full right now, or null if it can. Walks the book
     * without mutating it, and has to mirror {@link #match} order for order -- FOK only
     * means something if this and the real walk agree on where it stops.
     *
     * <p>Own orders never count. Under EXPIRE_MAKER, {@code match} expires them and keeps
     * walking, so everything past one still counts. Under EXPIRE_TAKER it stops dead at the
     * first one, so this stops there too and answers SELF_TRADE: the user's own order is what
     * blocked the fill, whatever liquidity lies beyond it. EXPIRE_BOTH never gets here: the
     * engine refuses it with FOK.
     */
    private RejectReason fokRejection(Order taker) {
        boolean stopsAtSelfMatch = taker.selfTradePreventionMode()
                != SelfTradePreventionMode.SELF_TRADE_PREVENTION_MODE_EXPIRE_MAKER;
        NavigableMap<Long, PriceLevel> opposite = taker.side() == Side.SIDE_BUY ? asks : bids;
        long available = 0;
        for (PriceLevel level : opposite.values()) {
            if (!crosses(taker, level.price())) {
                break;
            }
            for (Order maker : level.orders()) {
                if (maker.sameUserAs(taker)) {
                    if (stopsAtSelfMatch) {
                        return RejectReason.REJECT_REASON_SELF_TRADE;
                    }
                    continue;
                }
                available += maker.remaining();
                if (available >= taker.quantity()) {
                    return null;
                }
            }
        }
        return RejectReason.REJECT_REASON_INSUFFICIENT_LIQUIDITY;
    }
}
