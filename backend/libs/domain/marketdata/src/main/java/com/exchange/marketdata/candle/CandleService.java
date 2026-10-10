package com.exchange.marketdata.candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reading candles. Shared by web-bff (charts) and spot-open-api (the public
 * {@code /klines}-style endpoint), which is why it lives here rather than in either app
 * -- see AGENTS.md's "does more than one app need it?".
 *
 * <p>No rollup arithmetic: every interval in {@link CandleInterval} is materialised by
 * the writer, so each of these is a direct query. See that enum's javadoc for why
 * rolling up at read time was rejected.
 */
@Service
public class CandleService {

    /** A cap, not a default -- a client asking for a year of 1m candles gets the newest
     *  slice rather than a response big enough to hurt both ends. */
    public static final int MAX_CANDLES = 1_000;

    private final CandleRepository candles;

    public CandleService(CandleRepository candles) {
        this.candles = candles;
    }

    /** The newest {@code limit} candles, oldest first (chart order). */
    public List<Candle> latest(Market market, String symbol, CandleInterval interval, int limit) {
        List<Candle> newestFirst = candles.findLatest(market, symbol, interval, clamp(limit));
        return newestFirst.reversed();
    }

    /**
     * The newest {@code limit} buckets up to {@code end}, oldest first, with every bucket nothing
     * traded in filled by a flat bar at the previous close. Empty until the market's first trade:
     * there is no price to fill with, and inventing one would be a fabricated record.
     *
     * <p>{@code end} is "now" for a chart's first load and the bar before its oldest one when it
     * pages back; the fill rule is the same either way, so pages join without a seam.
     *
     * <p>Reads the newest {@code limit} stored candles at or before {@code end}, which reach back
     * at least as far as the filled window can, then walks forward bucket by bucket from the
     * older of the two starts. A page shorter than {@code limit} means the market's history is
     * exhausted, which is how a chart knows to stop asking.
     */
    public List<CandleBar> latestFilled(Market market, String symbol, CandleInterval interval,
                                        int limit, Instant end) {
        Instant last = interval.bucketStart(end);
        List<Candle> stored = candles.findLatestUpTo(market, symbol, interval, last, clamp(limit)).reversed();
        if (stored.isEmpty()) {
            return List.of();
        }
        Instant windowStart = last;
        for (int i = 1; i < clamp(limit); i++) {
            windowStart = interval.previous(windowStart);
        }
        Instant first = stored.get(0).getOpenTime().isAfter(windowStart) ? stored.get(0).getOpenTime() : windowStart;

        Map<Instant, Candle> byOpen = new HashMap<>();
        for (Candle c : stored) {
            byOpen.put(c.getOpenTime(), c);
        }
        List<CandleBar> bars = new ArrayList<>();
        long previousClose = stored.get(0).getClose();
        for (Instant at = first; !at.isAfter(last); at = interval.next(at)) {
            Candle c = byOpen.get(at);
            if (c != null) {
                bars.add(CandleBar.of(c));
                previousClose = c.getClose();
            } else if (at.isAfter(stored.get(0).getOpenTime())) {
                bars.add(CandleBar.flat(at, previousClose));
            }
        }
        return bars.size() <= clamp(limit) ? bars : bars.subList(bars.size() - clamp(limit), bars.size());
    }

    /** A window, oldest first. Both bounds are inclusive of the buckets they fall in --
     *  the caller gets whole candles, since half a candle is not a candle. */
    public List<Candle> range(Market market, String symbol, CandleInterval interval,
                              Instant from, Instant to) {
        List<Candle> found = candles.findRange(market, symbol, interval,
                interval.bucketStart(from), to);
        return found.size() <= MAX_CANDLES
                ? found
                : found.subList(found.size() - MAX_CANDLES, found.size());
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        return Math.min(limit, MAX_CANDLES);
    }
}
