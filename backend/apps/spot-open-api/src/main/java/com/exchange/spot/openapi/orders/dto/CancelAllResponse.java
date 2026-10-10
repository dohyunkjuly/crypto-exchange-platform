package com.exchange.spot.openapi.orders.dto;

/** How many open orders matched the filters and were sent a cancel. */
public record CancelAllResponse(int count) {
}
