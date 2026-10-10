package com.exchange.spot.stream;

import com.exchange.common.id.Ulid;
import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.FillSettled;

/**
 * One of this user's fills, settled -- with what it actually cost them.
 *
 * <p>Deliberately the same shape as {@code MyTradeView} on {@code GET /bff/trades}, so a client
 * renders a row from either with the same code. REST fills the trade-history panel on load,
 * this keeps it moving, and they must not look different.
 *
 * <p>The fee is the amount settlement charged, read back from the row rather than recomputed --
 * which is the entire reason this arrives over a settlement topic instead of being derived from
 * the engine's {@code TradeExecuted}, where only rates travel. <b>Negative is a rebate</b>: a
 * maker is paid for providing liquidity, and rendering that as a charge would be wrong rather
 * than merely odd.
 *
 * <p>No counterparty, because {@code FillSettled} has none to give.
 */
public record FillData(String tradeId,
                       String orderId,
                       String symbol,
                       String price,
                       String quantity,
                       String quoteQuantity,
                       String side,
                       boolean isMaker,
                       String fee,
                       String feeAsset) {

    public static FillData of(FillSettled fill, String feeAssetSymbol) {
        return new FillData(Ulid.toUuid(fill.getTradeId().toByteArray()).toString(),
                Ulid.toUuid(fill.getOrderId().toByteArray()).toString(),
                fill.getSymbol(),
                Money.toDecimal(fill.getPrice()),
                Money.toDecimal(fill.getQuantity()),
                Money.toDecimal(fill.getQuoteQuantity()),
                fill.getSide().name().replace("SIDE_", ""),
                fill.getIsMaker(),
                Money.toDecimal(fill.getFee()),
                feeAssetSymbol);
    }
}
