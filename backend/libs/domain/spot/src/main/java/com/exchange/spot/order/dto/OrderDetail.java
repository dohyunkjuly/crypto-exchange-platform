package com.exchange.spot.order.dto;

import com.exchange.spot.order.SpotOrder;

/**
 * An order together with the symbol code its {@code symbolId} refers to.
 *
 * <p>{@link SpotOrder} stores {@code symbolId}, a number, because that is what the
 * matching path needs and because this codebase does not put JPA relationships between
 * tables (see AGENTS.md). Every caller rendering an order to a human needs
 * {@code "BTCUSDT"} instead, so the lookup happens once, in bulk, inside
 * {@code OrderQueryService} -- rather than each app doing its own N+1 per row.
 */
public record OrderDetail(SpotOrder order, String symbol) {
}
