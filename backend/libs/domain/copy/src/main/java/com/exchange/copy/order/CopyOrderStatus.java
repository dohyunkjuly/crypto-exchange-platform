package com.exchange.copy.order;

public enum CopyOrderStatus {
    /** In the copy account, outcome not seen yet. */
    PLACED,
    /** Filled something. Resets the follow's failures in a row. */
    FILLED,
    /** Could not be placed, or filled nothing. Counts towards closing the account. */
    FAILED,
    /** Nothing to do, as a sell with nothing to sell: neither a failure nor a success. */
    SKIPPED
}
