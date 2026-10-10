package com.exchange.admin.bff.trades.dto;

import java.time.Instant;

/** The trades screen's shapes. */
public final class TradeViews {

    private TradeViews() {
    }

    /** One side of a trade. A fee of zero has no asset. */
    public record Party(Long userId, String email, String orderId, String fee, String feeAsset) {
    }

    public record Row(String id, String symbol, String base, String quote, String price, String quantity,
                      String quoteQuantity, Party buyer, Party seller, boolean buyerIsMaker, Instant executedAt) {
    }
}
