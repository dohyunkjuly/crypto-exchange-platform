package com.exchange.marketdata.stream;

import org.springframework.stereotype.Component;

/**
 * {@code trade} -- every fill in a market, as it happens.
 *
 * <p>Carries no state, so a new subscriber gets nothing until the next trade prints. That
 * is correct rather than a gap: there is no "current trade", and opening with an old one
 * would put a stale price on a live chart. A client that needs the last price has the REST
 * ticker for it.
 *
 * <p>Nothing here pushes. {@link MarketWsConsumer} reads the event stream and broadcasts on
 * this channel; this class exists so the name is a registered channel rather than a string
 * the handler happens to accept, and so an unknown channel is rejected in one place.
 */
@Component
public class TradeChannel implements WsChannel {

    public static final String NAME = "trade";

    @Override
    public String name() {
        return NAME;
    }
}
