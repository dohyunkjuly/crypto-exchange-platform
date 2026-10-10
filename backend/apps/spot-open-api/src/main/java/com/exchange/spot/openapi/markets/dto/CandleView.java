package com.exchange.spot.openapi.markets.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.candle.CandleBar;
import java.time.Instant;

/** One bucket. A quiet bucket comes back flat at the previous close with zero volume, so the
 *  series is continuous. */
public record CandleView(Instant openTime, String open, String high, String low, String close,
                         String volume, String quoteVolume, int tradeCount) {
    public static CandleView of(CandleBar b) {
        return new CandleView(b.openTime(), Money.toDecimal(b.open()), Money.toDecimal(b.high()),
                Money.toDecimal(b.low()), Money.toDecimal(b.close()), Money.toDecimal(b.volume()),
                Money.toDecimal(b.quoteVolume()), b.tradeCount());
    }
}
