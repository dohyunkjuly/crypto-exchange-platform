package com.exchange.spot.order.exception;

/** The symbol exists but isn't accepting new orders right now (pre-trading, halted,
 *  cancel-only, post-trading, or delisted). */
public class SymbolNotTradingException extends RuntimeException {

    public SymbolNotTradingException() {
        super("Symbol is not open for trading");
    }
}
