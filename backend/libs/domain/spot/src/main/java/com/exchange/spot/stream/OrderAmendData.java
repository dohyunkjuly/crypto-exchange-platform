package com.exchange.spot.stream;

import com.exchange.contracts.spot.v1.OrderAmendRejected;
import com.exchange.contracts.spot.v1.OrderAmended;

/**
 * The outcome of an amend, inside an {@link OrderUpdateData} frame. The PATCH answered 202
 * before the engine decided, so this frame is how a client learns which way it went.
 *
 * <p>{@code rejectReason} is the engine's enum name and null when the amend was applied. The
 * other fields are the order's terms afterwards, each null where the order has no such term
 * (or on a refusal, where nothing changed).
 */
public record OrderAmendData(String rejectReason, String price, String stopPrice, String quantity) {

    static OrderAmendData of(OrderAmended amended) {
        return new OrderAmendData(null,
                amended.hasNewPrice() ? OrderUpdateData.decimalOrNull(amended.getNewPrice()) : null,
                amended.hasNewStopPrice() ? OrderUpdateData.decimalOrNull(amended.getNewStopPrice()) : null,
                OrderUpdateData.decimalOrNull(amended.getNewQuantity()));
    }

    static OrderAmendData of(OrderAmendRejected rejected) {
        return new OrderAmendData(rejected.getReason().name(), null, null, null);
    }
}
