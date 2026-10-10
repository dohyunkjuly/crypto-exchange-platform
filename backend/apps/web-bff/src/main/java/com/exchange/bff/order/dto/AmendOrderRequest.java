package com.exchange.bff.order.dto;

/**
 * Every field is optional and an absent one keeps its value; which of them an order accepts
 * is the amend table in docs/reference/order-types.md. {@code quantity} is the new TOTAL
 * size, filled part included.
 */
public record AmendOrderRequest(String price, String stopPrice, String quantity, String quoteOrderQty) {
}
