package com.exchange.spot.openapi.markets.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.ticker.Ticker;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** A market's rolling 24-hour summary. Money is a decimal string; the change percent has two
 *  decimals, as a ticker shows it. */
public record TickerView(String symbol, String lastPrice, String openPrice, String highPrice, String lowPrice,
                         String priceChange, String priceChangePercent, String volume, String quoteVolume,
                         int tradeCount, Instant windowStart, Instant windowEnd) {

    public static TickerView of(Ticker t) {
        return new TickerView(t.symbol(), Money.toDecimal(t.lastPrice()), Money.toDecimal(t.openPrice()),
                Money.toDecimal(t.highPrice()), Money.toDecimal(t.lowPrice()), Money.toDecimal(t.priceChange()),
                BigDecimal.valueOf(t.priceChangeBps(), 2).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                Money.toDecimal(t.volume()), Money.toDecimal(t.quoteVolume()), t.tradeCount(),
                t.windowStart(), t.windowEnd());
    }
}
