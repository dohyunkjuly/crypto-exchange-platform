package com.exchange.marketdata.candle;

import java.time.Instant;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds a {@link Candle} the way MongoDB's mapper does.
 *
 * <p>{@code Candle} has no public constructor and no setters, deliberately: production
 * code never assembles one by hand -- the writer upserts field operators ({@code $max},
 * {@code $inc}) and the reader gets instances back from the mapper, which populates them
 * reflectively. Tests still need candles to read, so this does exactly what the mapper
 * does, in one place instead of in every test that wants one.
 *
 * <p>Lives in the entity's own package so the protected constructor is reachable without
 * subclassing it.
 */
public final class CandleFixture {

    private CandleFixture() {
    }

    /** All prices and quantities 1e8-scaled, same as the real thing. */
    public static Candle candle(Market market, String symbol, CandleInterval interval,
                                Instant openTime, long open, long high, long low, long close,
                                long volume, long quoteVolume, int tradeCount) {
        Candle candle = new Candle();
        set(candle, "id", Candle.idOf(market, symbol, interval, openTime));
        set(candle, "market", market);
        set(candle, "symbol", symbol);
        set(candle, "interval", interval);
        set(candle, "openTime", openTime);
        set(candle, "open", open);
        set(candle, "high", high);
        set(candle, "low", low);
        set(candle, "close", close);
        set(candle, "volume", volume);
        set(candle, "quoteVolume", quoteVolume);
        set(candle, "tradeCount", tradeCount);
        set(candle, "updatedAt", openTime);
        return candle;
    }

    private static void set(Candle candle, String field, Object value) {
        ReflectionTestUtils.setField(candle, field, value);
    }
}
