package com.exchange.spot.order;

import java.util.List;

public enum OrderStatus {
    /** A conditional order parked in the engine's trigger book, waiting for its stop
     *  price. Not resting, not matchable, not visible in depth. Becomes NEW once it fires
     *  and follows the normal lifecycle from there. */
    PENDING_TRIGGER,
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    PENDING_CANCEL,
    REJECTED,
    EXPIRED;

    /**
     * Still capable of filling, so it counts against the open-order caps and keeps a copy
     * account from being flat. PENDING_CANCEL is in: the cancel has been asked for, but the
     * engine has not confirmed it. Defined once here so the caps, the open-orders list, the
     * copier and the staff console can never disagree about it.
     */
    public static final List<OrderStatus> OPEN = List.of(PENDING_TRIGGER, NEW, PARTIALLY_FILLED, PENDING_CANCEL);

    /** Open, minus PENDING_CANCEL: its cancel is already on its way. */
    public static final List<OrderStatus> CANCELABLE = List.of(PENDING_TRIGGER, NEW, PARTIALLY_FILLED);

    /** Terminal states cannot change again, and the engine may drop them from the book. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED || this == EXPIRED;
    }
}
