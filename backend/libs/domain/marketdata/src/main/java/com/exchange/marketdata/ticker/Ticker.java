package com.exchange.marketdata.ticker;

import java.time.Instant;

/**
 * A symbol's rolling 24-hour summary.
 *
 * <p>Deliberately NOT stored anywhere. A 24h window slides at both ends -- trades arrive
 * at the front and age out at the back -- so a maintained document updated per trade
 * would need to subtract expiring trades to stay correct, which means querying them
 * anyway. Computing it on demand from candles that are already aggregated is both
 * cheaper and always right: see {@link TickerService}.
 *
 * <p>All prices and quantities are 1e8-scaled longs, same as {@link
 * com.exchange.marketdata.candle.Candle}. {@code priceChangeBps} is in basis points
 * rather than a percentage double, for the same reason -- no float ever touches money
 * here.
 */
public record Ticker(String symbol,
                     long openPrice,
                     long highPrice,
                     long lowPrice,
                     long lastPrice,
                     long priceChange,
                     int priceChangeBps,
                     long volume,
                     long quoteVolume,
                     int tradeCount,
                     Instant windowStart,
                     Instant windowEnd) {

    /** No trades in the window at all: every price is the last known close, so a chart
     *  shows a flat line rather than a hole, and volume is honestly zero. */
    public static Ticker flat(String symbol, long lastPrice, Instant start, Instant end) {
        return new Ticker(symbol, lastPrice, lastPrice, lastPrice, lastPrice,
                0L, 0, 0L, 0L, 0, start, end);
    }
}
