package com.exchange.spot.order.exception;

/** A BUY on a MARKET-firing order (MARKET, STOP_LOSS, TAKE_PROFIT) must lock a quote
 *  budget, since its execution price -- and so a base quantity's cost -- isn't known
 *  ahead of matching. See docs/reference/order-types.md. */
public class QuoteOrderQtyRequiredException extends RuntimeException {

    public QuoteOrderQtyRequiredException() {
        super("A market-firing BUY requires quoteOrderQty");
    }
}
