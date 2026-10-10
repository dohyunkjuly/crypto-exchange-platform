package com.exchange.spot.openapi.orders.dto;

/** 202: the cancel is queued, not applied. The row still says NEW at this instant, which is why
 *  the order is not echoed back -- it would read as "the cancel failed". */
public record CancelOrderResponse(String orderId, String status) {
    public static CancelOrderResponse canceling(String orderId) {
        return new CancelOrderResponse(orderId, "CANCELING");
    }
}
