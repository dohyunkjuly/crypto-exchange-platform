package com.exchange.spot.openapi.markets.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.tape.PublicTrade;
import java.time.Instant;

/** One public print: no user ids, no fees -- what a stranger may see. */
public record TradeView(long sequence, String price, String quantity, String quoteQuantity,
                        String takerSide, Instant time) {
    public static TradeView of(PublicTrade t) {
        return new TradeView(t.sequence(), Money.toDecimal(t.price()), Money.toDecimal(t.quantity()),
                Money.toDecimal(t.quoteQuantity()), t.takerSide(), t.executedAt());
    }
}
