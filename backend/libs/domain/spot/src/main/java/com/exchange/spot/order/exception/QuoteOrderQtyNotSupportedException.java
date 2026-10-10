package com.exchange.spot.order.exception;

/** {@code quoteOrderQty} given on a SELL, or on any order with a fixed price -- both
 *  have an unambiguous {@code quantity} instead. See docs/reference/order-types.md. */
public class QuoteOrderQtyNotSupportedException extends RuntimeException {

    public QuoteOrderQtyNotSupportedException() {
        super("quoteOrderQty is not valid for this order");
    }
}
