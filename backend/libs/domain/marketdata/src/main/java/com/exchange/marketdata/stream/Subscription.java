package com.exchange.marketdata.stream;

/**
 * One thing a connection is watching: a channel, a symbol, and an interval or grouping for the
 * channels that take one. A record, so it is a correct map key without a hand-rolled string
 * format every caller would have to build identically.
 *
 * <p><b>{@code interval} and {@code grouping} are named fields, not part of the channel name.</b>
 * the usual encoding -- {@code btcusdt@kline_1m} -- because those stream names are a public API
 * where one opaque string is easier to version. This socket serves {@code web/} alone (see
 * {@link MarketWsHandler}), so a client sends {@code "channel":"kline","interval":"1m"} and gets
 * a specific error when the interval is wrong rather than "unknown channel". Two fields rather
 * than one generic parameter because each channel uses exactly one and the acknowledgement
 * echoes back the name the client sent. A third parameterised channel is the moment to
 * reconsider.
 *
 * <p>Empty string, never null, for an unused field: {@code new Subscription("depth", "BTCUSDT",
 * null, null)} and one built with {@code ""} would be different keys for the same subscription.
 */
public record Subscription(String channel, String symbol, String interval, String grouping) {

    public Subscription {
        interval = interval == null ? "" : interval;
        grouping = grouping == null ? "" : grouping;
    }

    /** For a channel that carries neither, which is most of them. */
    public static Subscription of(String channel, String symbol) {
        return new Subscription(channel, symbol, "", "");
    }
}
