package com.exchange.spot.openapi.markets.dto;

import com.exchange.common.money.Money;
import com.exchange.spot.symbol.MarketSymbol;
import com.exchange.spot.symbol.SymbolStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** One market and the rules an order in it must satisfy (docs/reference/order-types.md
 *  "Bounds"). Open-api's own record: a contract must not move when web-bff's view does. A
 *  bound that is not configured is absent, never "0". */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SymbolView(String symbol, String baseAsset, String quoteAsset, SymbolStatus status,
                         String priceTick, String qtyStep, String minQty, String maxQty, String minNotional,
                         String marketMinQty, String marketMaxQty, String maxNotional,
                         String minQuoteQty, String maxQuoteQty,
                         int baseDecimals, int quoteDecimals,
                         boolean marketOrdersEnabled, boolean stopOrdersEnabled,
                         List<String> depthGroupings,
                         /** No engine is running this market right now: a new order or an amend is
                          *  refused with 5031 until one is. Cancels go through. */
                         boolean paused) {

    public static SymbolView of(MarketSymbol m, boolean paused) {
        return new SymbolView(m.symbol(), m.baseAsset(), m.quoteAsset(), m.status(),
                Money.toDecimal(m.priceTick()), Money.toDecimal(m.qtyStep()), Money.toDecimal(m.minQty()),
                Money.toDecimal(m.maxQty()), Money.toDecimal(m.minNotional()),
                optional(m.marketMinQty()), optional(m.marketMaxQty()), optional(m.maxNotional()),
                optional(m.minQuoteQty()), optional(m.maxQuoteQty()),
                m.baseDecimals(), m.quoteDecimals(), m.marketOrdersEnabled(), m.stopOrdersEnabled(),
                m.depthGroupings().stream().map(Money::toDecimal).toList(),
                paused);
    }

    private static String optional(Long scaled) {
        return scaled == null ? null : Money.toDecimal(scaled);
    }
}
