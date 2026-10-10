package com.exchange.spot.order.exception;

/** The market's command partition does not exist on the commands topic, so no order can be
 *  published for it. An operator error in {@code spot_symbols.command_partition}, not a user one. */
public class MarketUnavailableException extends RuntimeException {

    public MarketUnavailableException(String symbol) {
        super(symbol + " is not available right now");
    }
}
