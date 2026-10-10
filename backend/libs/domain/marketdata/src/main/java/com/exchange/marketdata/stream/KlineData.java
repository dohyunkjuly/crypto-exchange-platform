package com.exchange.marketdata.stream;

import com.exchange.common.money.Money;
import com.exchange.marketdata.candle.Candle;
import java.time.Instant;

/**
 * The body of a {@code kline} frame: one bar, in full.
 *
 * <p><b>An absolute bar state, not a delta.</b> Each frame replaces whatever the client
 * held for this {@code openTime}, exactly as {@link DepthData} does for the book, and for
 * the same reason: nothing accumulates on the client, so nothing can drift out of step.
 * That is what removes the whole snapshot-handshake problem -- a client can fetch history
 * over REST and subscribe in either order, because the first push overwrites the
 * in-progress bar with the truth regardless of what it missed in between.
 *
 * <p>It is also why this channel exists rather than leaving clients to fold trades into
 * bars themselves. That works -- {@code TradeData} carries the engine's timestamp and
 * sequence for exactly that -- but it makes every client implement bucket boundaries, gap
 * detection and a snapshot handshake, and re-derive numbers {@code market-data} has already
 * computed. These frames are what it wrote, not a second aggregation of the same trades.
 *
 * <p>{@code closed} is what lets a chart commit a bar and stop redrawing it. Derived from
 * the clock rather than stored, because a bucket becomes final by time passing, and nothing
 * writes a row to say so.
 *
 * <p>Money is a decimal string, never a JSON number -- the same rule every view here
 * follows.
 */
public record KlineData(String symbol,
                        String interval,
                        Instant openTime,
                        Instant closeTime,
                        String open,
                        String high,
                        String low,
                        String close,
                        String volume,
                        String quoteVolume,
                        int tradeCount,
                        boolean closed) {

    public static KlineData of(Candle candle, Instant now) {
        Instant closeTime = candle.getInterval().next(candle.getOpenTime());
        return new KlineData(candle.getSymbol(),
                candle.getInterval().code(),
                candle.getOpenTime(),
                closeTime,
                Money.toDecimal(candle.getOpen()),
                Money.toDecimal(candle.getHigh()),
                Money.toDecimal(candle.getLow()),
                Money.toDecimal(candle.getClose()),
                Money.toDecimal(candle.getVolume()),
                Money.toDecimal(candle.getQuoteVolume()),
                candle.getTradeCount(),
                !closeTime.isAfter(now));
    }
}
