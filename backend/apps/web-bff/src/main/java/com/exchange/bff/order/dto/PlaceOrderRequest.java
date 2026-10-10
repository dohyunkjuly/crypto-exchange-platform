package com.exchange.bff.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Cross-field requiredness (price for the LIMIT family, quoteOrderQty vs. quantity by
 * side, stopPrice unless trailing) depends on {@code orderType}/{@code side} together,
 * so it's checked in {@code OrderService}, not with annotations here -- see
 * docs/reference/order-types.md for the full table.
 *
 * <p>{@code side}, {@code orderType}, {@code timeInForce} and {@code selfTradePreventionMode}
 * are strings so a bad value gets its own error code: see {@code OrderRequestFields}.
 */
public record PlaceOrderRequest(
        @NotBlank String symbol,
        @NotBlank String side,
        @NotBlank String orderType,
        String timeInForce,
        String price,
        String stopPrice,
        String quantity,
        String quoteOrderQty,
        String clientOrderId,
        String selfTradePreventionMode,
        Integer trailingDeltaBps) {
}
