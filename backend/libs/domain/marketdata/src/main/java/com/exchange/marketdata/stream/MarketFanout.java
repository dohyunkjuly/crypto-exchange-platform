package com.exchange.marketdata.stream;

import java.util.Set;

/** What a publisher needs from the socket: who is watching what, and a way to send to them.
 *  An interface so the publishers, which the handler must not depend on, do not depend on the
 *  handler either -- the constructor cycle both apps' sockets exist to avoid. */
public interface MarketFanout {

    /** Fans one message out to everyone holding this exact subscription. */
    void broadcast(Subscription subscription, WsMessage<?> payload);

    /** The subscriptions on this channel with at least one subscriber right now. */
    Set<Subscription> watched(String channel);
}
