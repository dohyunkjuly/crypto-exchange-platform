package com.exchange.marketdata.stream;

/** The per-connection guards the shared handler enforces; each app sets its own numbers. */
public record SocketLimits(int maxSubscriptionsPerConnection, int sendTimeLimitMs, int sendBufferLimitBytes) {
}
