package com.exchange.spot.openapi.orders.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** The order guide's parameters (docs/reference/order-types.md), verbatim. Sizing rules and
 *  bounds are the shared service's; this only carries the fields. The four enum fields are
 *  strings so a bad value gets its own error code: see {@code OrderRequestFields}. */
public record PlaceOrderRequest(@NotBlank String symbol,
                                @NotBlank @Schema(allowableValues = {"BUY", "SELL"}) String side,
                                @NotBlank @Schema(allowableValues = {"LIMIT", "MARKET", "STOP_LOSS",
                                        "STOP_LOSS_LIMIT", "TAKE_PROFIT", "TAKE_PROFIT_LIMIT",
                                        "LIMIT_MAKER"}) String orderType,
                                @Schema(allowableValues = {"GTC", "IOC", "FOK"}) String timeInForce,
                                String price,
                                String stopPrice,
                                String quantity,
                                String quoteOrderQty,
                                String clientOrderId,
                                @Schema(allowableValues = {"EXPIRE_TAKER", "EXPIRE_MAKER", "EXPIRE_BOTH"})
                                String selfTradePreventionMode,
                                Integer trailingDeltaBps) {
}
