package com.exchange.bff.market.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.tape.PublicTrade;
import java.time.Instant;

/**
 * One printed trade, as the browser sees it.
 *
 * <p>Deliberately the same shape as {@code marketws}'s {@code TradeData}, minus the symbol:
 * a client asked for one market's tape, so repeating the symbol on all fifty rows is bytes
 * for nothing. A page can render a row from this endpoint and a row pushed over the socket
 * with the same code, which is the point -- REST fills the panel on load, the socket keeps
 * it moving, and they must not look different.
 *
 * <p>Carries no counterparty and no fees, because {@link PublicTrade} does not have them to
 * give. See that type for why.
 *
 * <p>Money is a decimal string, never a JSON number -- the rule every view here follows.
 */
public record TradeView(long sequence,
                        String price,
                        String quantity,
                        String quoteQuantity,
                        String takerSide,
                        Instant time) {

    public static TradeView of(PublicTrade trade) {
        return new TradeView(trade.sequence(),
                Money.toDecimal(trade.price()),
                Money.toDecimal(trade.quantity()),
                Money.toDecimal(trade.quoteQuantity()),
                trade.takerSide(),
                trade.executedAt());
    }
}
