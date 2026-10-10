package com.exchange.admin.bff.orders.dto;

import java.time.Instant;
import java.util.List;

/** The orders screen's shapes. */
public final class OrderViews {

    private OrderViews() {
    }

    /** One order as a list row shows it. Amounts are decimal strings; a market order has no price. */
    public record Row(String id, String symbol, String base, String quote, Long userId, String userEmail, String side,
                      String type, String status, String price, String stopPrice, String quantity,
                      String quoteOrderQty, String filled, String quoteFilled, String clientOrderId,
                      Instant createdAt, Instant updatedAt) {
    }

    public record Page_(List<Row> items, long total, int page, int size) {
    }

    /** A fill, from the order's side of it: maker or taker, and what it paid in fees. No fee, no fee asset. */
    public record Fill(String tradeId, String price, String quantity, String quoteQuantity, boolean maker,
                       String fee, String feeAsset, Instant executedAt) {
    }

    public record Detail(Row order, List<Fill> fills) {
    }
}
