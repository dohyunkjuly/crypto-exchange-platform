package com.exchange.spot.order.exception;

/** A second amend while the first is still on its way to the engine. One at a time: see
 *  V4__spot.sql for how two computed from the same stale row leave an order under-locked. */
public class AmendPendingException extends RuntimeException {

    public AmendPendingException() {
        super("an amend of this order is still being processed");
    }
}
