package com.exchange.spot.openapi.markets.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthLevel;
import java.util.List;

/** The book, best price first on both sides, stamped with the engine's sequence so a client
 *  can order it against the socket's frames. A never-quoted symbol is an empty ladder at
 *  sequence 0, not a 404: the market exists, it simply has no orders yet. */
public record DepthView(String symbol, long lastSequence, List<LevelView> bids, List<LevelView> asks) {

    public record LevelView(String price, String quantity) {
        static LevelView of(DepthLevel level) {
            return new LevelView(Money.toDecimal(level.price()), Money.toDecimal(level.quantity()));
        }
    }

    public static DepthView of(Depth depth) {
        return new DepthView(depth.symbol(), depth.lastSequence(),
                depth.bids().stream().map(LevelView::of).toList(),
                depth.asks().stream().map(LevelView::of).toList());
    }

    public static DepthView empty(String symbol) {
        return new DepthView(symbol, 0L, List.of(), List.of());
    }
}
