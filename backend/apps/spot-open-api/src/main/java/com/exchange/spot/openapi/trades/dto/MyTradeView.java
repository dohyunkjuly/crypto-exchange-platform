package com.exchange.spot.openapi.trades.dto;

import com.exchange.common.money.Money;
import com.exchange.spot.trade.dto.UserTrade;
import java.time.Instant;
import java.util.UUID;

/** One of this user's fills, with what it actually cost them. {@code fee} negative is a rebate.
 *  The same shape the socket's {@code fills} frame carries, so one renderer serves both. */
public record MyTradeView(UUID tradeId, String symbol, UUID orderId, String orderType, String price,
                          String quantity, String quoteQuantity, String side, boolean isMaker, String fee,
                          String feeAsset, Instant time) {
    public static MyTradeView of(UserTrade t) {
        return new MyTradeView(t.tradeId(), t.symbol(), t.orderId(),
                t.orderType() == null ? null : t.orderType().name(),
                Money.toDecimal(t.price()), Money.toDecimal(t.quantity()), Money.toDecimal(t.quoteQuantity()),
                t.side().name(), t.isMaker(), Money.toDecimal(t.fee()), t.feeAsset(), t.executedAt());
    }
}
