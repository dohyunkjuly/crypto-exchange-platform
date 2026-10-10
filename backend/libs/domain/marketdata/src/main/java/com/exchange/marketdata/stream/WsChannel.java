package com.exchange.marketdata.stream;

import java.util.Optional;

/**
 * One kind of thing a client can subscribe to on the market socket.
 *
 * <p>A client subscribes to a channel <em>and</em> a symbol, because the two vary
 * independently: a trading page wants trades and depth for one market, while a watchlist
 * wants one channel across many. Before this existed a subscription was a symbol alone,
 * which was only enough while there was exactly one thing to send.
 *
 * <p>Implementations exist mainly so the set of valid channel names is a list of beans
 * rather than a growing switch inside {@link MarketWsHandler}, and so the handler can
 * check a subscription and hand a new subscriber its opening frame without knowing what
 * either means. The handler stays free of anything domain-shaped; each channel keeps its
 * own knowledge.
 *
 * <p>A channel implementation must not depend on {@link MarketWsHandler} -- the handler
 * collects these at construction, so anything pushing <em>into</em> the handler belongs in
 * a separate bean ({@code DepthPublisher}, {@code KlinePublisher}) rather than here.
 */
public interface WsChannel {

    /** The name a client sends, e.g. {@code "trade"}. Lower case, stable. */
    String name();

    /**
     * Rejects a subscription this channel cannot serve, by throwing one of the plain exceptions in
     * {@code stream.exception}; the owning app renders it with its own code.
     *
     * <p>Runs <b>before</b> the subscription is recorded and acknowledged, which is the
     * whole point: a client that got a {@code subscribed} frame and then an error would
     * have to unpick which of its subscriptions actually took.
     *
     * <p>The default accepts anything. A channel with no interval deliberately does not
     * reject one that arrives -- an ignored field is not a failure, and refusing it would
     * make an otherwise harmless client bug fatal.
     */
    default void validate(Subscription subscription) {
    }

    /**
     * What this channel sends a client the instant it subscribes, if anything.
     *
     * <p>The distinction is whether the channel carries <b>state</b> or <b>events</b>. An
     * order book and a candle are state: a subscriber who waited for the next change would
     * render an empty ladder or a missing bar until then, which on a quiet market is a long
     * time. Trades are events; there is no "current trade" to open with, and replaying an
     * old one would print a stale price onto a live chart.
     */
    default Optional<WsMessage<?>> initialFrame(Subscription subscription) {
        return Optional.empty();
    }
}
