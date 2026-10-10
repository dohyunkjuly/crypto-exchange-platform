package com.exchange.spot.order.dto;

import com.exchange.common.money.Money;
import com.exchange.contracts.spot.v1.OrderAccepted;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import java.time.Instant;

/**
 * One order as a client sees it, open or closed -- the row on the private socket's acceptance
 * frame, shared by web-bff and spot-open-api because the frame is. Each app's REST view has the
 * same fields; this is the one the shared consumer builds. Money is a decimal string; a
 * quote-sized order has no base {@code quantity} and a base-sized one no {@code quoteOrderQty}.
 */
public record OrderSnapshot(String orderId,
                        String clientOrderId,
                        String symbol,
                        String side,
                        String orderType,
                        String timeInForce,
                        String status,
                        String price,
                        String stopPrice,
                        Integer trailingDeltaBps,
                        String triggerDirection,
                        String quantity,
                        String quoteOrderQty,
                        String filledQuantity,
                        String remainingQuantity,
                        String cumulativeQuoteQuantity,
                        String averageFillPrice,
                        String rejectReason,
                        Instant createdAt,
                        Instant updatedAt,
                        boolean amendPending) {

    public static OrderSnapshot of(OrderDetail detail) {
        SpotOrder order = detail.order();
        return new OrderSnapshot(order.getId().toString(),
                order.getClientOrderId(),
                detail.symbol(),
                order.getSide().name(),
                order.getOrderType().name(),
                order.getTimeInForce() == null ? null : order.getTimeInForce().name(),
                order.getStatus().name(),
                money(order.getPrice()),
                money(order.getStopPrice()),
                order.getTrailingDeltaBps(),
                triggerDirection(order.getOrderType(), order.getSide()),
                // A quote-quantity order was never sized in base units, so reporting
                // "0" would be wrong rather than merely unhelpful.
                order.getQuoteOrderQty() == null ? Money.toDecimal(order.getQuantity()) : null,
                money(order.getQuoteOrderQty()),
                Money.toDecimal(order.getFilledQuantity()),
                remaining(order),
                Money.toDecimal(order.getCumulativeQuoteQuantity()),
                Money.toDecimal(order.averageFillPrice()),
                order.getRejectReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.isAmendPending());
    }

    /**
     * The same row, from the engine's acceptance instead of the database, for the private
     * socket. Lives beside {@link #of} so the two cannot drift: an open-orders list mixes rows
     * from both and must not be able to tell them apart. Nothing has filled yet -- the caller
     * only builds this when the event is the whole order (see {@code UserWsConsumer}).
     */
    public static OrderSnapshot accepted(String orderId, String symbol, OrderAccepted accepted,
                                     String status, Instant at) {
        OrderType type = OrderType.valueOf(accepted.getOrderType().name().replace("ORDER_TYPE_", ""));
        Side side = Side.valueOf(accepted.getSide().name().replace("SIDE_", ""));
        var tif = accepted.getTimeInForce();
        boolean quoteSized = accepted.hasQuoteOrderQty();
        String quantity = quoteSized ? null : Money.toDecimal(accepted.getQuantity());
        return new OrderSnapshot(orderId,
                accepted.hasClientOrderId() ? accepted.getClientOrderId() : null,
                symbol,
                side.name(),
                type.name(),
                tif == com.exchange.contracts.spot.v1.TimeInForce.TIME_IN_FORCE_UNSPECIFIED
                        ? null : tif.name().replace("TIME_IN_FORCE_", ""),
                status,
                accepted.hasPrice() ? Money.toDecimal(accepted.getPrice()) : null,
                accepted.hasStopPrice() ? Money.toDecimal(accepted.getStopPrice()) : null,
                accepted.hasTrailingDeltaBps() ? accepted.getTrailingDeltaBps() : null,
                triggerDirection(type, side),
                quantity,
                quoteSized ? Money.toDecimal(accepted.getQuoteOrderQty()) : null,
                Money.toDecimal(0L),
                quantity,
                Money.toDecimal(0L),
                Money.toDecimal(0L),
                null,
                at,
                at,
                false);
    }

    /**
     * Which way the last price must cross {@code stopPrice} to fire: "RISE" (at or above) or
     * "FALL" (at or below). Null for a type with no trigger.
     *
     * <p>Duplicates the engine's rule, which lives on proto enums a domain type cannot share.
     * A stop-loss sell guards against a fall, a take-profit sell waits for a rise, and a buy
     * inverts both; {@code OrderViewTest} pins all four.
     */
    static String triggerDirection(OrderType type, Side side) {
        return switch (type) {
            case STOP_LOSS, STOP_LOSS_LIMIT -> side == Side.SELL ? "FALL" : "RISE";
            case TAKE_PROFIT, TAKE_PROFIT_LIMIT -> side == Side.SELL ? "RISE" : "FALL";
            case LIMIT, MARKET, LIMIT_MAKER -> null;
        };
    }

    /**
     * Null for a quote-quantity order.
     *
     * <p>{@code SpotOrder.remainingQuantity()} is {@code quantity - filledQuantity}, and
     * {@code quantity} stays zero for the whole life of a {@code quoteOrderQty} order
     * (see its javadoc) -- so that subtraction goes negative the moment such an order
     * fills at all. What remains there is a quote budget, not a base quantity, and it is
     * already reported as {@code quoteOrderQty} minus {@code cumulativeQuoteQuantity}.
     */
    private static String remaining(SpotOrder order) {
        return order.getQuoteOrderQty() == null
                ? Money.toDecimal(order.remainingQuantity())
                : null;
    }

    private static String money(Long scaled) {
        return scaled == null ? null : Money.toDecimal(scaled);
    }
}
