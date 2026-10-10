package com.exchange.bff.order.dto;

import com.exchange.common.money.Money;
import com.exchange.spot.order.OrderType;
import com.exchange.spot.order.Side;
import com.exchange.spot.order.SpotOrder;
import com.exchange.spot.order.dto.OrderDetail;
import java.time.Instant;

/**
 * One order, as the browser sees it.
 *
 * <p>Same rule as every other view here: money is a decimal string, never a JSON number
 * (see {@code CandleView}). Nullable money stays null rather than becoming "0" -- a
 * MARKET order has no price, and rendering that as zero would be a lie a chart or a
 * confirmation dialog would happily repeat.
 *
 * <p>A trailing order's {@code stopPrice} is its activation price, never the live stop: the
 * engine ratchets that in memory and nothing persists it, so this view cannot report it.
 */
public record OrderView(String orderId,
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

    public static OrderView of(OrderDetail detail) {
        SpotOrder order = detail.order();
        return new OrderView(order.getId().toString(),
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
