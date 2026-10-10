package com.exchange.spot.order.exception;

/** {@code SpotSymbol.marketOrdersEnabled} is false for this symbol. */
public class MarketOrdersDisabledException extends RuntimeException {

    public MarketOrdersDisabledException() {
        super("Market orders are disabled for this symbol");
    }
}
