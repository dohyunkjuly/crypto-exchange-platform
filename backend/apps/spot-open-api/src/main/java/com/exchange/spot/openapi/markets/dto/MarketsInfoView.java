package com.exchange.spot.openapi.markets.dto;

import java.util.List;

/** {@code GET /v1/markets/info}: every listed market, and the limits this API enforces, so a
 *  client reads them rather than guessing. {@code type} is {@code REQUEST_WEIGHT} or
 *  {@code ORDERS}; {@code window} is {@code 1m}, {@code 10s} or {@code 1d}. */
public record MarketsInfoView(List<SymbolView> symbols, List<RateLimitView> rateLimits) {
    public record RateLimitView(String type, String window, int limit) {
    }
}
