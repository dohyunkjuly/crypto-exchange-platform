package com.exchange.bff.symbol.dto;

import com.exchange.spot.symbol.MarketSymbol;
import com.exchange.common.money.Money;
import com.exchange.spot.symbol.SymbolStatus;
import java.util.List;

/**
 * One tradable market, as the browser sees it: what it is, and what an order in it must
 * look like.
 *
 * <p>Everything the order form needs to validate before it submits anything. Without this
 * a client can only guess at tick size and minimums, discover it guessed wrong from a
 * rejected order, and show the user an error for something the form could have prevented.
 *
 * <p>{@code depthGroupings} is the order book's grouping selector, so the options come from
 * the server rather than being hardcoded per market in the UI -- they depend on the tick and
 * the price, and a client cannot work out the second of those.
 *
 * <p>Deliberately a subset of {@code SpotSymbol}. {@code priceBandBps},
 * {@code maxOpenOrders} and the conditional-order caps are operator controls enforced
 * server side; publishing them would invite a client to enforce them too, which is a
 * second implementation of a rule that only counts once.
 *
 * <p>Money is a decimal string, never a JSON number -- the rule every view in this app
 * follows. A tick size a browser reads as a double is how an order form starts rounding
 * prices wrong.
 */
public record SymbolView(String symbol,
                         String baseAsset,
                         String quoteAsset,
                         SymbolStatus status,
                         String priceTick,
                         String qtyStep,
                         String minQty,
                         String maxQty,
                         String minNotional,
                         String marketMinQty,
                         String marketMaxQty,
                         String maxNotional,
                         String minQuoteQty,
                         String maxQuoteQty,
                         int baseDecimals,
                         int quoteDecimals,
                         boolean marketOrdersEnabled,
                         boolean stopOrdersEnabled,
                         List<String> depthGroupings,
                         /** No engine is running this market right now: new orders are refused with 5031,
                          *  cancels go through. Read at request time, not cached with the rest. */
                         boolean paused) {

    public static SymbolView of(MarketSymbol market, boolean paused) {
        return new SymbolView(market.symbol(),
                market.baseAsset(),
                market.quoteAsset(),
                market.status(),
                Money.toDecimal(market.priceTick()),
                Money.toDecimal(market.qtyStep()),
                Money.toDecimal(market.minQty()),
                Money.toDecimal(market.maxQty()),
                Money.toDecimal(market.minNotional()),
                optional(market.marketMinQty()), optional(market.marketMaxQty()), optional(market.maxNotional()),
                optional(market.minQuoteQty()), optional(market.maxQuoteQty()),
                // How many decimals to render, which is not the same question as what the
                // tick allows: a market can tick in 0.01 while the asset itself is shown to
                // 8 places in a balance.
                market.baseDecimals(),
                market.quoteDecimals(),
                market.marketOrdersEnabled(),
                market.stopOrdersEnabled(),
                market.depthGroupings().stream().map(Money::toDecimal).toList(),
                paused);
    }

    /** A bound that is not configured is null on the wire, never "0": zero would read as "nothing allowed". */
    private static String optional(Long scaled) {
        return scaled == null ? null : Money.toDecimal(scaled);
    }
}
