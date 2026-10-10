package com.exchange.core.api.error;

/**
 * A stable code and message, on any transport -- a WebSocket error frame has no HTTP
 * status, so that half lives on {@link HttpErrorCode} instead.
 *
 * <p>Codes are each app's own contract, never centralised (AGENTS.md).
 * {@link CommonErrorCode} covers only what the shared handler itself needs; everything
 * app-specific gets its own enum in that app's package.
 *
 * <p>Ranges, so a number hints at where it came from without opening the file:
 * <pre>
 *   1000s  request-shape problems (validation, bad input) -- caught before domain logic runs
 *   2000s  auth (credentials, sessions, tokens)
 *   3000s  rate limiting / abuse prevention
 *   4000s  wallet (balances, assets)
 *   5000s  orders (symbols, sizing, limits, lifecycle)
 *   6000s  market data over REST
 *   7000s  WebSocket -- codes for a transport with no status line, so these implement
 *          this interface directly rather than {@link HttpErrorCode}
 *   9000s  the catch-all -- anything mapped here is a bug, not a user mistake
 * </pre>
 * Guidance, not a global registry: two apps may each use 5000s, since a code only means
 * something alongside the app that issued it. <b>Within one app they stay disjoint even
 * across enums</b> -- web-bff splits its codes by transport, and a number must identify
 * itself without the reader knowing which enum to open.
 */
public interface ErrorCode {

    int getCode();

    String getMessage();
}
