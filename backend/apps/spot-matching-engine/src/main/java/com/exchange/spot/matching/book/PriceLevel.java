package com.exchange.spot.matching.book;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Every order resting at one price, in arrival order.
 *
 * <p>A {@link LinkedHashMap} rather than a queue: iteration is insertion-ordered, which
 * gives time priority for free, and removal by id is O(1), which is what a cancel needs.
 * A plain deque would make cancelling an order in the middle of a busy level O(n).
 *
 * <p>{@code totalQuantity} is maintained incrementally rather than summed on demand,
 * because L2 depth reads it constantly and the level can hold thousands of orders.
 */
final class PriceLevel {

    private final long price;
    private final Map<UUID, Order> orders = new LinkedHashMap<>();
    private long totalQuantity;

    PriceLevel(long price) {
        this.price = price;
    }

    long price() {
        return price;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orders.size();
    }

    boolean isEmpty() {
        return orders.isEmpty();
    }

    void add(Order order) {
        orders.put(order.id(), order);
        totalQuantity += order.remaining();
    }

    Order remove(UUID orderId) {
        Order removed = orders.remove(orderId);
        if (removed != null) {
            totalQuantity -= removed.remaining();
        }
        return removed;
    }

    /** Called after an order at this level is partially filled. */
    void reduceTotal(long amount) {
        totalQuantity -= amount;
    }

    /** Insertion-ordered, so the first element is the one with time priority. */
    Collection<Order> orders() {
        return orders.values();
    }
}
