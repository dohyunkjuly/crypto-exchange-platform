package com.exchange.spot.order;

import com.exchange.spot.symbol.SpotSymbol;

/**
 * What an open order holds locked, as one formula. The API uses it to size an amend's extra
 * lock and settlement to size every release, and the two must agree to the unit or funds
 * stay locked forever or are released twice.
 */
public final class OrderLock {

    private OrderLock() {
    }

    /** A buy locks the quote asset it will pay with, a sell the base asset it will deliver. */
    public static Long assetId(SpotSymbol symbol, SpotOrder order) {
        return order.getSide() == Side.SELL ? symbol.getBaseAssetId() : symbol.getQuoteAssetId();
    }

    /**
     * @param price     the limit price; ignored for a sell and for a quote-sized order
     * @param remaining what is left, in the order's own sizing unit: the unspent budget for
     *                  a quote-sized order, base quantity otherwise
     */
    public static long amount(SpotSymbol symbol, SpotOrder order, Long price, long remaining) {
        if (order.getSide() == Side.SELL || order.getQuoteOrderQty() != null) {
            return remaining;
        }
        return symbol.notionalOf(price, remaining);
    }

    /**
     * What to lock before publishing an amend so the order cannot end up short, whatever
     * fills between this read and the engine applying it.
     *
     * <p>The extra the amend needs is {@code new(f) - old(f)} for the filled amount {@code f}
     * at that moment, which is linear in {@code f}: it grows with {@code f} when the price
     * drops and the size grows. So the row's own {@code filled} is not the worst case by
     * itself -- the other end, everything that can still fill, has to be tried too.
     *
     * @param oldTotal and {@code newTotal}, {@code filled}: in the order's own sizing unit
     */
    public static long additionalFor(SpotSymbol symbol, SpotOrder order, Long newPrice,
                                     long oldTotal, long newTotal, long filled) {
        long mostFilled = Math.min(oldTotal, newTotal);
        long extra = 0;
        for (long f : new long[] {filled, mostFilled}) {
            extra = Math.max(extra, amount(symbol, order, newPrice, newTotal - f)
                    - amount(symbol, order, order.getPrice(), oldTotal - f));
        }
        return extra;
    }

    /** What this order holds right now, from its own row. */
    public static long amount(SpotSymbol symbol, SpotOrder order) {
        long remaining = order.getQuoteOrderQty() != null
                ? order.getQuoteOrderQty() - order.getCumulativeQuoteQuantity()
                : order.remainingQuantity();
        return amount(symbol, order, order.getPrice(), remaining);
    }
}
