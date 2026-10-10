package com.exchange.marketdata.depth;

/**
 * One price level: how much is resting at one price, on one side.
 *
 * <p>Both fields are 1e8-scaled longs, the same convention as {@link
 * com.exchange.marketdata.candle.Candle} and every money column in MySQL. No float ever
 * touches a price here -- an order book that disagrees with the trade tape in the seventh
 * decimal is a support ticket.
 *
 * <p>{@code quantity} is the sum of every resting order at this price, not one order.
 * That is what makes this L2: the book says 2.5 BTC is bid at 60,000, never who bid it.
 */
public record DepthLevel(long price, long quantity) {
}
