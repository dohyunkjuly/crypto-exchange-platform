package com.exchange.bff.order.dto;

/**
 * 202 -- the cancel is queued, not applied. See {@code OrderController#cancel}.
 *
 * <p>Carries no status deliberately: at the moment this is returned, nobody knows yet
 * whether the cancel beat an in-flight fill. Echoing the order id back keeps the
 * response envelope consistent with every other endpoint rather than sending an empty
 * body a client would have to special-case.
 */
public record CancelOrderResponse(String orderId) {
}
