package com.exchange.bff.market.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.candle.CandleBar;
import java.time.Instant;

/**
 * One OHLCV bucket, as the browser sees it.
 *
 * <p>Prices and quantities are decimal strings, never JSON numbers -- the same rule every
 * other view here follows (see {@code BalanceView}). A candle is stored as a 1e8-scaled
 * long precisely so it never touches a float; serialising it as a JSON number would hand
 * it straight to JavaScript's double and undo that at the last step.
 */
public record CandleView(Instant openTime,
                         String open,
                         String high,
                         String low,
                         String close,
                         String volume,
                         String quoteVolume,
                         int tradeCount) {

    public static CandleView of(CandleBar bar) {
        return new CandleView(bar.openTime(),
                Money.toDecimal(bar.open()),
                Money.toDecimal(bar.high()),
                Money.toDecimal(bar.low()),
                Money.toDecimal(bar.close()),
                Money.toDecimal(bar.volume()),
                Money.toDecimal(bar.quoteVolume()),
                bar.tradeCount());
    }
}
