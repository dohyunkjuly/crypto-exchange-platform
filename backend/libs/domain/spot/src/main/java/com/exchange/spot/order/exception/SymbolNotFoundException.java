package com.exchange.spot.order.exception;

/** No symbol matches the given code. */
public class SymbolNotFoundException extends RuntimeException {

    public SymbolNotFoundException(String symbol) {
        super("No such symbol: " + symbol);
    }
}
