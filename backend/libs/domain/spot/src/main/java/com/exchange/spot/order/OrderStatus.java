package com.exchange.spot.order;

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

    /** Terminal states cannot change again, and the engine may drop them from the book. */
    public boolean isTerminal() {
        return this == FILLED || this == CANCELED || this == REJECTED || this == EXPIRED;
    }
}
