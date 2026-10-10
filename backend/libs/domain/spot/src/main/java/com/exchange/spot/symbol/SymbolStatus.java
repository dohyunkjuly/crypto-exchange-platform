package com.exchange.spot.symbol;

public enum SymbolStatus {
    /** Listed, not yet open. */
    PRE_TRADING,
    TRADING,
    /** Temporarily stopped; open orders stand. */
    HALTED,
    /** No new orders, cancels allowed — how a market is wound down. */
    CANCEL_ONLY,
    POST_TRADING,
    DELISTED
}
