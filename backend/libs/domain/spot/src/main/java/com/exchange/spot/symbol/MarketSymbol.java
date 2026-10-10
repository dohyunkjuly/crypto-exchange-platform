package com.exchange.spot.symbol;

import java.util.List;

/**
 * One tradable market, resolved: its assets as codes and its filters as 1e8-scaled longs.
 *
 * <p>The internal shape, not a response. {@code SymbolView} renders this for a browser with
 * money as decimal strings; the WebSocket needs the raw numbers to check a subscription
 * against. Keeping them separate is what stops the socket parsing decimal strings back into
 * longs to answer a question it had the longs for all along.
 */
public record MarketSymbol(String symbol,
                           String baseAsset,
                           String quoteAsset,
                           SymbolStatus status,
                           long priceTick,
                           long qtyStep,
                           long minQty,
                           long maxQty,
                           long minNotional,
                           /** The market-order bounds (docs/reference/order-types.md "Bounds"); null means
                            *  the limit-order column applies, or no bound for the quote pair. */
                           Long marketMinQty,
                           Long marketMaxQty,
                           Long maxNotional,
                           Long minQuoteQty,
                           Long maxQuoteQty,
                           int baseDecimals,
                           int quoteDecimals,
                           boolean marketOrdersEnabled,
                           boolean stopOrdersEnabled,
                           List<Long> depthGroupings,
                           /** Which engine partition runs it: the key into {@link EnginePresence}, so a view
                            *  can say whether the market is paused right now. Not a response field. */
                           int commandPartition) {
}
