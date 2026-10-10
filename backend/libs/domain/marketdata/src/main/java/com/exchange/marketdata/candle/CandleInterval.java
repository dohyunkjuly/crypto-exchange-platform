package com.exchange.marketdata.candle;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The candle sizes this exchange publishes.
 *
 * <p>Every one is <b>materialised</b> -- a bucket per interval is written as each trade
 * arrives, rather than storing 1m and rolling up at read time. Rolling up sounds cheaper and is
 * not: 500 daily candles would mean reading 720,000 one-minute documents per request.
 *
 * <p>{@code code} is the wire name, the one every venue uses, because clients and charting libraries already
 * speak it. Never rename one: it goes out in responses and is embedded in every candle's id.
 *
 * <p><b>Candles are never deleted, and there is no mechanism here to delete them.</b> There was
 * one -- per-interval retention enforced by a TTL index -- removed rather than turned off,
 * because a switch that quietly destroys market history should not sit within reach of a
 * one-line change. The reasoning behind it was wrong twice: every serious venue serves 1m
 * klines back to listing, and a candle is 293 bytes, so twelve markets across six intervals
 * cost about a gigabyte a year. It was also unrecoverable -- nothing here reads
 * {@code spot_trades}, so a deleted candle could only come back by replaying the event topic.
 */
public enum CandleInterval {

    ONE_MINUTE("1m", Duration.ofMinutes(1)),
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
    THIRTY_MINUTES("30m", Duration.ofMinutes(30)),
    ONE_HOUR("1h", Duration.ofHours(1)),
    TWO_HOURS("2h", Duration.ofHours(2)),
    FOUR_HOURS("4h", Duration.ofHours(4)),
    SIX_HOURS("6h", Duration.ofHours(6)),
    EIGHT_HOURS("8h", Duration.ofHours(8)),
    TWELVE_HOURS("12h", Duration.ofHours(12)),
    ONE_DAY("1d", Duration.ofDays(1)),
    /** Calendar months, UTC: the one interval whose buckets are not all the same length, so
     *  {@link #length()} is nominal and anything stepping between buckets must use {@link #next}. */
    ONE_MONTH("1M", Duration.ofDays(30));

    private final String code;
    private final Duration length;

    CandleInterval(String code, Duration length) {
        this.code = code;
        this.length = length;
    }

    public String code() {
        return code;
    }

    /** Nominal for {@link #ONE_MONTH}; step buckets with {@link #next}, never by adding this. */
    public Duration length() {
        return length;
    }

    /** The open time of the bucket before the one opening at {@code bucketStart}. */
    public Instant previous(Instant bucketStart) {
        if (this == ONE_MONTH) {
            return bucketStart.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
        }
        return bucketStart.minus(length);
    }

    /** The open time of the bucket after the one opening at {@code bucketStart}. */
    public Instant next(Instant bucketStart) {
        if (this == ONE_MONTH) {
            return bucketStart.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
        }
        return bucketStart.plus(length);
    }

    /**
     * The start of the bucket {@code at} falls into -- floor division against the epoch.
     *
     * <p>Correct for every interval here because each one divides evenly into a day and
     * the epoch itself is midnight UTC, so buckets always land on the boundaries a chart
     * expects (a 4h candle at 00:00/04:00/08:00..., a daily candle at midnight UTC).
     * That would stop being true for an interval like 1w, which needs an explicit
     * week-start convention rather than epoch arithmetic -- so add one only with that in
     * mind. {@link #ONE_MONTH} is the calendar case, handled by name.
     */
    public Instant bucketStart(Instant at) {
        if (this == ONE_MONTH) {
            ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
            return utc.withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS).toInstant();
        }
        long lengthMs = length.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(at.toEpochMilli(), lengthMs) * lengthMs);
    }

    /** @throws IllegalArgumentException no interval has this code -- callers rendering an
     *          API error should catch it and map to their own error code, per
     *          AGENTS.md (a shared module owns no ErrorCode). */
    public static CandleInterval ofCode(String code) {
        for (CandleInterval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("unknown candle interval: " + code);
    }
}
