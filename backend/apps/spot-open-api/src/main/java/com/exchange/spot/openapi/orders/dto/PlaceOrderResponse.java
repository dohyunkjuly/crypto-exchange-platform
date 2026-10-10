package com.exchange.spot.openapi.orders.dto;

import java.time.Instant;

/** 202: funds locked and the command durable; matching is still to come. Enough to log the
 *  acceptance without a second read. {@code status} is NEW, or PENDING_TRIGGER for a
 *  conditional order. A replayed {@code clientOrderId} answers the original order. */
public record PlaceOrderResponse(String orderId, String clientOrderId, String symbol, String side,
                                 String orderType, String status, Instant transactTime) {
    public static PlaceOrderResponse of(OrderView order) {
        return new PlaceOrderResponse(order.orderId(), order.clientOrderId(), order.symbol(), order.side(),
                order.orderType(), order.status(), order.createdAt());
    }
}
