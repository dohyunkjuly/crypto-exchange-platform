package com.exchange.spot.stream;

import com.exchange.spot.order.dto.OrderSnapshot;
import com.exchange.common.money.Money;
import java.time.Instant;

/**
 * One change to one of this user's orders.
 *
 * <p>The single frame the private socket sends. Open Orders and Order History are the same
 * rows at different statuses, so one update serves both tabs: an order that fills leaves the
 * first and appears in the second.
 *
 * <p><b>Deliberately not a fill.</b> Everything here comes from the order's own state, which
 * the engine already reports -- status and what is left. Pushing fills instead would mean
 * deriving "which fee is mine" from the wire's taker/maker, while {@code TradeQueryService}
 * derives it from the database's buyer/seller: the same rule, two source shapes, two chances
 * to invert it and show somebody the counterparty's fee. A client wanting fill detail
 * refetches {@code /bff/trades}, which is the one implementation.
 *
 * <p>{@code rejectReason} and {@code cancelReason} are the engine's own enum names, present
 * only on the status that produces them. A client should switch on them rather than parse
 * any message: the reason is the contract, the wording is not.
 *
 * <p>{@code amend} is set on the two frames an amend produces and null on every other. On a
 * refused amend {@code status} is null too: the order is whatever it was, and this event does
 * not say what that is.
 *
 * <p>{@code order} is the whole row, in {@code GET /bff/orders}'s shape, on an acceptance whose
 * event describes all of it -- so an open-orders list can add the row without a request. Null
 * otherwise, including an order that filled on arrival: its average price is not on the event.
 *
 * <p>Money is a decimal string, never a JSON number -- the rule every view in this app
 * follows.
 */
public record OrderUpdateData(String orderId,
                              String symbol,
                              String status,
                              String executedQuantity,
                              String remainingQuantity,
                              String lastFillPrice,
                              String lastFillQuantity,
                              String rejectReason,
                              String cancelReason,
                              String clientOrderId,
                              Instant time,
                              OrderSnapshot order,
                              OrderAmendData amend) {

    /** Every frame but an amend's. */
    public OrderUpdateData(String orderId, String symbol, String status, String executedQuantity,
                           String remainingQuantity, String lastFillPrice, String lastFillQuantity,
                           String rejectReason, String cancelReason, String clientOrderId,
                           Instant time, OrderSnapshot order) {
        this(orderId, symbol, status, executedQuantity, remainingQuantity, lastFillPrice,
                lastFillQuantity, rejectReason, cancelReason, clientOrderId, time, order, null);
    }

    /** Builder for the statuses that carry no fill and no reason. */
    public static OrderUpdateData of(String orderId, String symbol, String status,
                                     String clientOrderId, Instant time) {
        return new OrderUpdateData(orderId, symbol, status, null, null, null, null,
                null, null, clientOrderId, time, null);
    }

    public static String decimalOrNull(Long scaled) {
        return scaled == null ? null : Money.toDecimal(scaled);
    }
}
