package com.exchange.spot.order;

public enum OrderType {
    LIMIT,
    MARKET,
    STOP_LOSS,
    STOP_LOSS_LIMIT,
    TAKE_PROFIT,
    TAKE_PROFIT_LIMIT,
    /** Rejected outright if it would take, so it can only ever be a maker. */
    LIMIT_MAKER
}
