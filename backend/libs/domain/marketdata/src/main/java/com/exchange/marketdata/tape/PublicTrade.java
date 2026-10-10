package com.exchange.marketdata.tape;

import java.time.Instant;

/**
 * One printed trade, as the whole world may see it.
 *
 * <p><b>What is missing is the point.</b> {@code spot_trades} in MySQL records who traded
 * with whom and what each paid in fees; none of that appears here. A public tape that named
 * counterparties would let anyone watching reconstruct a competitor's positions from the
 * screen, and fee rates are commercially specific to an account. This carries only what a
 * price is: how much, at what, when, and which side crossed the spread.
 *
 * <p>{@code takerSide} is the aggressor -- {@code BUY} means someone lifted an offer, which
 * is what colours a tape row green. It says nothing about who: the maker resting on the
 * other side is not identified either.
 *
 * <p>Prices and quantities are 1e8-scaled longs, the same convention as every other money
 * value here.
 */
public record PublicTrade(long sequence,
                          long price,
                          long quantity,
                          long quoteQuantity,
                          String takerSide,
                          Instant executedAt) {
}
