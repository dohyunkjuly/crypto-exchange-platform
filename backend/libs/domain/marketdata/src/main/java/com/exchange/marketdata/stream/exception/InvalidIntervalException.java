package com.exchange.marketdata.stream.exception;

/** A kline subscription named an interval that does not exist. Plain, per AGENTS.md: the app
 *  that owns the socket maps it to its own code. */
public class InvalidIntervalException extends RuntimeException {
    public InvalidIntervalException(String interval) {
        super("Unknown interval: " + interval);
    }
}
