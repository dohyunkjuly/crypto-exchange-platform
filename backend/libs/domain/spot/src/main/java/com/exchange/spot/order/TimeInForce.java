package com.exchange.spot.order;

public enum TimeInForce {
    /** Rests until filled or cancelled. */
    GTC,
    /** Fills what it can immediately, cancels the rest. */
    IOC,
    /** Fills entirely and immediately, or not at all. */
    FOK,
    /** Rests until a deadline. Unsupported. */
    GTD
}
