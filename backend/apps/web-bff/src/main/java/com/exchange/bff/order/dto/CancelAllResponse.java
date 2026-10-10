package com.exchange.bff.order.dto;

/**
 * 202 -- the cancels are queued. {@code count} is how many open orders matched when they were
 * sent, not how many the engine canceled: a fill racing a cancel can still win.
 */
public record CancelAllResponse(int count) {
}
