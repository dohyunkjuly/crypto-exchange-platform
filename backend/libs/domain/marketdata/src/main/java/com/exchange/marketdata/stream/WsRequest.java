package com.exchange.marketdata.stream;

import java.util.Locale;

/**
 * The whole inbound contract:
 * {@code {"id":7,"action":"subscribe","channel":"kline","symbol":"BTCUSDT","interval":"1m"}}.
 *
 * <p>{@code id} is optional and opaque -- echoed back on the reply so a client with several
 * requests in flight can tell which one an error refers to.
 *
 * <p>{@code channel} defaults to {@code "trade"}, what the socket carried before there was
 * anything else; {@code grouping} is empty for the raw ladder, which is also the cheap path
 * (precomputed array, versus reading the whole ladder); {@code interval} is empty unless the
 * channel needs one, and is lower-cased so {@code "1M"} and {@code "1m"} are one request.
 * Whether a value names a real interval is the channel's judgement, not this record's.
 *
 * <p>The compact constructor <b>normalises rather than validates</b>: null becomes empty so the
 * handler needs no null checks, and the symbol is upper-cased once. Whether a blank action is an
 * error is the handler's call.
 */
public record WsRequest(Long id, String action, String channel, String symbol,
                        String interval, String grouping,
                        /** The {@code auth} action's credentials (open-api); empty on every other message. */
                        String apiKey, String timestamp, String signature) {

    /** What a request with no channel means. Not a reference to {@code TradeChannel.NAME}:
     *  this is the wire's default and must not silently change if that constant does. */
    private static final String DEFAULT_CHANNEL = "trade";

    public WsRequest {
        action = action == null ? "" : action.trim();
        // Blank, not just null: {"channel":""} is a client sending nothing meaningful, and
        // treating it as an unknown channel would be a confusing error for a missing field.
        channel = channel == null || channel.isBlank()
                ? DEFAULT_CHANNEL
                : channel.trim().toLowerCase(Locale.ROOT);
        symbol = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        interval = interval == null ? "" : interval.trim().toLowerCase(Locale.ROOT);
        grouping = grouping == null ? "" : grouping.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        timestamp = timestamp == null ? "" : timestamp.trim();
        signature = signature == null ? "" : signature.trim();
    }
}
