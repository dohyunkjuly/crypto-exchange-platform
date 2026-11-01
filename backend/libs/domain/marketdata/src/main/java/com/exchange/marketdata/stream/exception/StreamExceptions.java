package com.exchange.marketdata.stream.exception;

/**
 * Every way the market socket can be refused, in one place. Plain exceptions with no error
 * code: each app maps them to its own (docs/reference/error-codes.md). One file so the full
 * list reads at a glance; one class each so an advice can still dispatch on the type.
 */
public final class StreamExceptions {

    private StreamExceptions() {
    }

    /** A depth subscription named a grouping the market does not offer, or one that cannot be read. */
    public static class InvalidGroupingException extends RuntimeException {
        public InvalidGroupingException(String message) {
            super(message);
        }
    }

    /** A kline subscription named an interval that does not exist. Plain, per AGENTS.md: the app
     *  that owns the socket maps it to its own code. */
    public static class InvalidIntervalException extends RuntimeException {
        public InvalidIntervalException(String interval) {
            super("Unknown interval: " + interval);
        }
    }
}
