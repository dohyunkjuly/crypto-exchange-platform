package com.exchange.spot.order.exception;

import java.util.UUID;

/**
 * No such order <b>for this user</b>.
 *
 * <p>Deliberately the same answer for "does not exist" and "belongs to somebody else":
 * telling a caller that an order id exists but is not theirs confirms the id is real,
 * which is exactly what an attacker enumerating ids is trying to learn. Order ids are
 * ULIDs, so they are guessable in bulk by timestamp prefix -- this is not a theoretical
 * concern.
 */
public class OrderNotFoundException extends RuntimeException {

    /** By a client's own order id, which is a string the client chose. */
    public OrderNotFoundException(String clientOrderId) {
        super("No order with clientOrderId " + clientOrderId);
    }

    public OrderNotFoundException(UUID orderId) {
        super("No such order: " + orderId);
    }
}
