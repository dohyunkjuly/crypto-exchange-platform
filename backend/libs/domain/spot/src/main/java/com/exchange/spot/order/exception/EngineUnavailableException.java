package com.exchange.spot.order.exception;

/** No matching engine has announced this market's partition recently
 *  ({@code EnginePresence}), so a new order would only queue. Cancelling is never refused. */
public class EngineUnavailableException extends RuntimeException {

    public EngineUnavailableException() {
        super("Trading on this market is paused");
    }
}
