package com.exchange.bff.trade.dto;

import com.exchange.common.money.Money;
import com.exchange.spot.trade.dto.UserTrade;
import java.time.Instant;
import java.util.UUID;

/**
 * One of the signed-in user's own fills, as the browser sees it.
 *
 * <p>Answers the question an order list cannot: a market order that filled in twelve pieces
 * is one row there, with an average price and no fee at all. Here it is twelve rows, each
 * with the price it actually got and what it cost.
 *
 * <p>Carries no counterparty, because {@link UserTrade} has none to give -- the side was
 * chosen in the domain, and the other person's id and fee never left it.
 *
 * <p>{@code orderId} is this user's own order, so a client can group fills under the order
 * that produced them without a second request.
 *
 * <p>Money is a decimal string, never a JSON number -- the rule every view here follows.
 * {@code fee} can be negative: a maker rebate is a real thing and rendering it as a payment
 * would be wrong rather than merely odd.
 */
public record MyTradeView(UUID tradeId,
                          String symbol,
                          UUID orderId,
                          String orderType,
                          String price,
                          String quantity,
                          String quoteQuantity,
                          String side,
                          boolean isMaker,
                          String fee,
                          String feeAsset,
                          Instant time) {

    public static MyTradeView of(UserTrade trade) {
        return new MyTradeView(trade.tradeId(),
                trade.symbol(),
                trade.orderId(),
                trade.orderType() == null ? null : trade.orderType().name(),
                Money.toDecimal(trade.price()),
                Money.toDecimal(trade.quantity()),
                Money.toDecimal(trade.quoteQuantity()),
                trade.side().name(),
                trade.isMaker(),
                Money.toDecimal(trade.fee()),
                trade.feeAsset(),
                trade.executedAt());
    }
}
