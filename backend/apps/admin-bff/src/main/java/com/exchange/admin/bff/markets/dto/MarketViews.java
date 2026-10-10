package com.exchange.admin.bff.markets.dto;

import com.exchange.spot.symbol.SymbolStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

/** The Markets page's shapes. Amounts are decimal strings, as everywhere. */
public final class MarketViews {

    private MarketViews() {
    }

    /** The limits as the form edits them: decimal strings, null for "no limit". */
    public record LimitsBody(@NotNull String minQty, @NotNull String maxQty, @NotNull String minNotional,
                             String maxNotional, String marketMinQty, String marketMaxQty, String minQuoteQty,
                             String maxQuoteQty, Integer priceBandBps, int maxOpenOrders,
                             int maxOpenConditionalOrders, boolean marketOrdersEnabled, boolean stopOrdersEnabled) {
    }

    public record Row(Long id, String symbol, String base, String quote, String status,
                      int commandPartition, String priceTick, String qtyStep, LimitsBody limits, Instant createdAt) {
    }

    public record AssetOption(Long id, String symbol, String name) {
    }

    /** What the page needs: the markets, and what a new one may be made of. */
    public record Listing(List<Row> markets, List<AssetOption> assets, int partitionCount) {
    }

    public record CreateBody(@NotNull Long baseAssetId, @NotNull Long quoteAssetId, int commandPartition,
                             @NotNull String priceTick, @NotNull String qtyStep, @NotNull @Valid LimitsBody limits) {
    }

    public record StatusBody(@NotNull SymbolStatus status) {
    }
}
