package com.exchange.spot.order.exception;

/** {@code selfTradePreventionMode} is not one of the three modes. There is no mode that
 *  allows a self-trade: settlement records a trade as one buyer and one seller. */
public class InvalidSelfTradePreventionModeException extends RuntimeException {

    public InvalidSelfTradePreventionModeException(String value) {
        super("selfTradePreventionMode must be one of EXPIRE_TAKER, EXPIRE_MAKER or EXPIRE_BOTH, "
                + "got '" + value + "'");
    }
}
