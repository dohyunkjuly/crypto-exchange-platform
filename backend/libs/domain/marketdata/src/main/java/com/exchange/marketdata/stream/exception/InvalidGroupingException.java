package com.exchange.marketdata.stream.exception;

/** A depth subscription named a grouping the market does not offer, or one that cannot be read. */
public class InvalidGroupingException extends RuntimeException {
    public InvalidGroupingException(String message) {
        super(message);
    }
}
