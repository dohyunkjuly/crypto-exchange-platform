package com.exchange.marketdata.candle;

import java.time.Instant;

/**
 * One bar as a reader sees it: a stored {@link Candle}, or a flat one standing in for a bucket
 * nothing traded in. Storage stays sparse -- a candle exists only where a trade happened -- and
 * the gaps are filled here, at read time, so a chart's time axis stays continuous.
 */
public record CandleBar(Instant openTime, long open, long high, long low, long close,
                        long volume, long quoteVolume, int tradeCount) {

    public static CandleBar of(Candle c) {
        return new CandleBar(c.getOpenTime(), c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                c.getVolume(), c.getQuoteVolume(), c.getTradeCount());
    }

    /** No trade: every price is the previous close, and nothing changed hands. */
    public static CandleBar flat(Instant openTime, long previousClose) {
        return new CandleBar(openTime, previousClose, previousClose, previousClose, previousClose, 0, 0, 0);
    }
}
