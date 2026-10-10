package com.exchange.bff.order.dto;

/** 202: the amend is queued, not applied. The order is not echoed back, because the row still
 *  has its old terms at this instant and would read as "the amend failed". */
public record AmendOrderResponse(String orderId, String status) {
    public static AmendOrderResponse amending(String orderId) {
        return new AmendOrderResponse(orderId, "AMENDING");
    }
}
