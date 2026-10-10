package com.exchange.marketdata.stream;

/**
 * The frames the shared socket sends when a client gets the protocol wrong -- supplied by the
 * app that owns the socket, because error codes are each app's own contract (AGENTS.md) and this
 * module owns none. {@link #render} covers everything a channel throws, including the plain
 * exceptions in {@code stream.exception} and whatever a domain service raises; the named
 * methods are the handler's own protocol failures, which happen before any channel is involved.
 */
public interface ProtocolErrors {

    WsMessage<Void> malformed();

    WsMessage<Void> symbolRequired(Long id);

    WsMessage<Void> unknownAction(Long id, String action);

    WsMessage<Void> tooManySubscriptions(Long id);

    WsMessage<Void> unknownChannel(Long id, String channel);

    /** Anything thrown while handling a request: a named failure with its code, or a bug logged
     *  loudly and reported opaquely. */
    WsMessage<Void> render(Long id, Throwable failure);
}
