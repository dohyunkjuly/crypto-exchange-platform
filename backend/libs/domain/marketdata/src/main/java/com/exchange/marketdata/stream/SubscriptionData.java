package com.exchange.marketdata.stream;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The body of a {@code subscribed}/{@code unsubscribed} frame. Echoes every part of the
 * subscription so a client can tell which one changed even when it sent no correlation id
 * -- the symbol alone stopped being enough once one connection could hold several channels
 * of the same market, and several intervals of the same channel.
 *
 * <p>{@code interval} and {@code grouping} are omitted rather than sent empty when unused,
 * so a client testing {@code "grouping" in data} does not find a blank one.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SubscriptionData(String channel, String symbol, String interval,
                               String grouping) {
}
