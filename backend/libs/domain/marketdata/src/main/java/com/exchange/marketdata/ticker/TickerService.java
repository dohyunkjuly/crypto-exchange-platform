package com.exchange.marketdata.ticker;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleRepository;
import com.exchange.marketdata.candle.Market;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The rolling 24h ticker, computed on demand rather than stored -- see {@link Ticker}
 * for why maintaining one would be wrong, not merely expensive.
 *
 * <p>Cheap because it aggregates <b>already-aggregated</b> data: 24 hourly candles, not
 * the millions of trades behind them. The window's edges are therefore whole hours, so
 * the reported figures cover the last 24 complete hourly buckets plus the current partial
 * one -- up to an hour of slack at the trailing edge. That is the same approximation
 * every major exchange's 24h ticker makes, and the alternative (exact edges) means going
 * back to individual trades for the sake of a rounding difference nobody can act on.
 *
 * <p>Callers should cache the result for a few seconds; it is a summary, not a quote.
 * A live last-price belongs on the websocket feed, not on a REST ticker.
 */
@Service
public class TickerService {

    private static final CandleInterval WINDOW_UNIT = CandleInterval.ONE_HOUR;
    private static final int WINDOW_BUCKETS = 24;

    private final CandleRepository candles;
    private final Clock clock;

    // @Autowired is needed here and nowhere else in this codebase for one reason: this
    // class has TWO constructors (the second is the Clock test seam below), and with more
    // than one Spring cannot guess -- it looks for a no-arg constructor instead and fails
    // at startup with "No default constructor found". Caught by CandleBuildingIT actually
    // booting the app, which a compile could never have told us.
    @Autowired
    public TickerService(CandleRepository candles) {
        this(candles, Clock.systemUTC());
    }

    /** Test seam -- a fixed Clock makes the window deterministic. */
    TickerService(CandleRepository candles, Clock clock) {
        this.candles = candles;
        this.clock = clock;
    }

    /**
     * @return the summary, or empty if this symbol has never traded at all (no candles
     *         exist). A symbol that traded once a year ago yields a flat ticker, not an
     *         empty one -- "no activity recently" and "no such market" are different
     *         answers, and only the second is absent.
     */
    public Optional<Ticker> of(Market market, String symbol) {
        Instant now = clock.instant();
        Instant windowEnd = WINDOW_UNIT.bucketStart(now).plus(WINDOW_UNIT.length());
        Instant windowStart = windowEnd.minus(WINDOW_UNIT.length().multipliedBy(WINDOW_BUCKETS));

        List<Candle> window = candles.findRange(market, symbol, WINDOW_UNIT,
                windowStart, windowEnd);
        if (!window.isEmpty()) {
            return Optional.of(summarise(symbol, window, windowStart, windowEnd));
        }

        // Nothing in the last 24h. Fall back to the most recent close so the symbol still
        // reports a price -- but only if it has ever traded.
        return candles.findMostRecent(market, symbol, WINDOW_UNIT)
                .map(last -> Ticker.flat(symbol, last.getClose(), windowStart, windowEnd));
    }

    private static Ticker summarise(String symbol, List<Candle> window,
                                    Instant windowStart, Instant windowEnd) {
        Candle first = window.get(0);
        Candle last = window.get(window.size() - 1);

        long high = Long.MIN_VALUE;
        long low = Long.MAX_VALUE;
        long volume = 0;
        long quoteVolume = 0;
        int trades = 0;
        for (Candle candle : window) {
            high = Math.max(high, candle.getHigh());
            low = Math.min(low, candle.getLow());
            volume += candle.getVolume();
            quoteVolume += candle.getQuoteVolume();
            trades += candle.getTradeCount();
        }

        long open = first.getOpen();
        long lastPrice = last.getClose();
        long change = lastPrice - open;
        // Integer bps rather than a percentage double -- see Ticker's javadoc. Guarded
        // because an open of zero would only happen for a corrupt bucket, and dividing
        // by it should not take the whole endpoint down.
        int changeBps = open == 0 ? 0 : (int) (change * 10_000 / open);

        return new Ticker(symbol, open, high, low, lastPrice, change, changeBps,
                volume, quoteVolume, trades, windowStart, windowEnd);
    }
}
