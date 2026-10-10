package com.exchange.spot.order;

/** Which open orders a bulk cancel covers: resting on the book, parked waiting for a trigger, or both. */
public enum OpenOrderScope {
    ALL,
    BOOK,
    TRIGGERS
}
