package com.exchange.spot.order.exception;

/** {@code orderType} is not one of {@link com.exchange.spot.order.OrderType}'s names. */
public class InvalidOrderTypeException extends RuntimeException {

    public InvalidOrderTypeException(String value) {
        super("orderType must be one of LIMIT, MARKET, STOP_LOSS, STOP_LOSS_LIMIT, TAKE_PROFIT, "
                + "TAKE_PROFIT_LIMIT or LIMIT_MAKER, got '" + value + "'");
    }
}
