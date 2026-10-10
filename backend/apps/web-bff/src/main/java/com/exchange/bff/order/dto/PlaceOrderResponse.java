package com.exchange.bff.order.dto;

/** 202 -- durably queued, not yet matched. See {@code OrderController}. */
public record PlaceOrderResponse(String orderId) {
}
