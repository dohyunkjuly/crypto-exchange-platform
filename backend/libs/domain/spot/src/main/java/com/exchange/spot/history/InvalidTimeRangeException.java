package com.exchange.spot.history;

/** A history window that runs backwards, is empty, or is longer than {@link TimeRange#MAX}. */
public class InvalidTimeRangeException extends RuntimeException {

    public InvalidTimeRangeException() {
        super("from must be before to, and the range at most 90 days");
    }
}
