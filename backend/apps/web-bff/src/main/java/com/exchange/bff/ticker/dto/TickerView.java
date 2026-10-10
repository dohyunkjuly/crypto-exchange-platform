package com.exchange.bff.ticker.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.ticker.Ticker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A symbol's rolling 24h summary, as the browser sees it.
 *
 * <p>Same rule as {@link CandleView}: money is a decimal string, never a JSON number.
 *
 * <p>{@code priceChangePercent} is derived here rather than in the domain. {@link Ticker}
 * carries integer basis points on purpose -- no float ever touches money on the way in --
 * and a percentage is a presentation concern, so the conversion belongs at the edge that
 * renders it.
 */
public record TickerView(String symbol,
                         String lastPrice,
                         String openPrice,
                         String highPrice,
                         String lowPrice,
                         String priceChange,
                         String priceChangePercent,
                         String volume,
                         String quoteVolume,
                         int tradeCount,
                         Instant windowStart,
                         Instant windowEnd) {

    public static TickerView of(Ticker ticker) {
        return new TickerView(ticker.symbol(),
                Money.toDecimal(ticker.lastPrice()),
                Money.toDecimal(ticker.openPrice()),
                Money.toDecimal(ticker.highPrice()),
                Money.toDecimal(ticker.lowPrice()),
                Money.toDecimal(ticker.priceChange()),
                percent(ticker.priceChangeBps()),
                Money.toDecimal(ticker.volume()),
                Money.toDecimal(ticker.quoteVolume()),
                ticker.tradeCount(),
                ticker.windowStart(),
                ticker.windowEnd());
    }

    /** 250 bps -> "2.50". Two decimal places because that is what a price ticker shows;
     *  the exact figure is still available as basis points on the domain object. */
    private static String percent(int bps) {
        return BigDecimal.valueOf(bps, 2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
