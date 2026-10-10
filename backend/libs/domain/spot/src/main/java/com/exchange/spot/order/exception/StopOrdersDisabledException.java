package com.exchange.spot.order.exception;

/** {@code SpotSymbol.stopOrdersEnabled} is false for this symbol. */
public class StopOrdersDisabledException extends RuntimeException {

    public StopOrdersDisabledException() {
        super("Stop/take-profit orders are disabled for this symbol");
    }
}
