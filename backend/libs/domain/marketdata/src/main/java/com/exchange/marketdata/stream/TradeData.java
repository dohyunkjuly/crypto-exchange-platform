package com.exchange.marketdata.stream;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.Event;
import com.exchange.contracts.spot.v1.Side;
import com.exchange.contracts.spot.v1.TradeExecuted;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One printed trade -- the {@code data} of a {@code trade} frame, pushed to every browser
 * watching that symbol.
 *
 * <p>Money is a decimal string, the same rule {@code CandleView} and {@code TickerView}
 * follow -- a 1e8-scaled long serialised as a JSON number lands in JavaScript's double,
 * which is the one thing the scaling exists to prevent.
 *
 * <p>{@code sequence} is carried so a client can tell it missed something: the engine's
 * sequence is per-symbol, monotonic and dense, so 100 followed by 102 means a gap. The
 * client's response is to re-fetch candles over REST, not to ask for a replay -- the
 * socket is best-effort and REST is authoritative.
 */
public record TradeData(String symbol,
                        long sequence,
                        String price,
                        String quantity,
                        String quoteQuantity,
                        String takerSide,
                        Instant time) {

    public static TradeData of(Event event) {
        TradeExecuted trade = event.getTradeExecuted();
        return new TradeData(event.getSymbol(),
                event.getSequence(),
                Money.toDecimal(trade.getPrice()),
                Money.toDecimal(trade.getQuantity()),
                Money.toDecimal(trade.getQuoteQuantity()),
                trade.getTakerSide() == Side.SIDE_BUY ? "BUY" : "SELL",
                // The engine's own timestamp, never a clock here -- two consumers of the
                // same event must agree on when it happened.
                Instant.EPOCH.plus(event.getTimestampUs(), ChronoUnit.MICROS));
    }
}
