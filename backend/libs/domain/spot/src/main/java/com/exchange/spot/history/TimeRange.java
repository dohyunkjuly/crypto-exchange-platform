package com.exchange.spot.history;

import java.time.Duration;
import java.time.Instant;

/**
 * A history query's window: {@code from} inclusive, {@code to} exclusive, at most {@link #MAX}
 * long. The cap bounds what one request can scan; looking further back takes several requests,
 * the trade-off every history screen makes.
 */
public record TimeRange(Instant from, Instant to) {

    public static final Duration MAX = Duration.ofDays(90);
    /** What a history screen shows before the user picks anything. */
    public static final Duration DEFAULT = Duration.ofDays(7);

    public TimeRange {
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(MAX) > 0) {
            throw new InvalidTimeRangeException();
        }
    }

    /** Either end may be omitted: {@code to} defaults to now, {@code from} to {@link #DEFAULT} before it. */
    public static TimeRange of(Instant from, Instant to, Instant now) {
        Instant end = to == null ? now : to;
        return new TimeRange(from == null ? end.minus(DEFAULT) : from, end);
    }
}
