package com.exchange.spot.openapi.orders.dto;

import com.exchange.common.money.Money;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.dto.OrderDetail;
import java.time.Instant;

/**
 * One order, open or closed. Open-api's own copy of web-bff's view: a contract must not move
 * when the UI's does. Money is a decimal string. {@code quantity} is absent on a quote-sized
 * order and {@code quoteOrderQty} on a base-sized one: reporting "0" for the one that never
 * applied would be wrong rather than merely unhelpful.
 */
public record OrderView(String orderId, String clientOrderId, String symbol, String side, String orderType,
                        String timeInForce, String status, String price, String stopPrice,
                        Integer trailingDeltaBps, String triggerDirection, String quantity, String quoteOrderQty,
                        String filledQuantity, String remainingQuantity, String cumulativeQuoteQuantity,
                        String averageFillPrice, String rejectReason, Instant createdAt, Instant updatedAt,
                        boolean amendPending) {

    public static OrderView of(OrderDetail detail) {
        SpotOrder o = detail.order();
        boolean quoteSized = o.getQuoteOrderQty() != null;
        return new OrderView(o.getId().toString(), o.getClientOrderId(), detail.symbol(),
                o.getSide().name(), o.getOrderType().name(),
                o.getTimeInForce() == null ? null : o.getTimeInForce().name(),
                o.getStatus().name(), money(o.getPrice()), money(o.getStopPrice()), o.getTrailingDeltaBps(),
                triggerDirection(o.getOrderType(), o.getSide()),
                quoteSized ? null : Money.toDecimal(o.getQuantity()), money(o.getQuoteOrderQty()),
                Money.toDecimal(o.getFilledQuantity()),
                quoteSized ? null : Money.toDecimal(o.remainingQuantity()),
                Money.toDecimal(o.getCumulativeQuoteQuantity()), Money.toDecimal(o.averageFillPrice()),
                o.getRejectReason(), o.getCreatedAt(), o.getUpdatedAt(), o.isAmendPending());
    }

    /** Which way the last price must move to fire a conditional order; null for the rest. */
    static String triggerDirection(OrderType type, Side side) {
        return switch (type) {
            case STOP_LOSS, STOP_LOSS_LIMIT -> side == Side.SELL ? "FALL" : "RISE";
            case TAKE_PROFIT, TAKE_PROFIT_LIMIT -> side == Side.SELL ? "RISE" : "FALL";
            case LIMIT, MARKET, LIMIT_MAKER -> null;
        };
    }

    private static String money(Long scaled) {
        return scaled == null ? null : Money.toDecimal(scaled);
    }
}
