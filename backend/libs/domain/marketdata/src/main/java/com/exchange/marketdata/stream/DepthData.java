package com.exchange.marketdata.stream;

import com.exchange.common.money.Money;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthLevel;
import java.util.List;

/**
 * The body of a {@code depth} frame: the visible top of the book, in full.
 *
 * <p><b>A complete refresh, not a diff.</b> Each frame replaces the last one, so there is
 * no accumulated state on the client to get out of step -- a dropped frame costs 100ms of
 * staleness and then fixes itself. The alternative, streaming per-level deltas, is smaller
 * on the wire but requires the client to hold a book, detect gaps in {@code lastSequence},
 * and re-fetch a REST snapshot when it finds one; getting any of that wrong leaves a book
 * that is quietly wrong rather than visibly broken. At the twenty levels a trading page
 * shows, the refresh is small enough that the trade is not worth making. Deep books and
 * the diff protocol belong to spot-open-api's clients, who need the full ladder.
 *
 * <p>{@code lastSequence} still travels, even though nothing here has to reconcile against
 * it: it is how a client can tell a frame it has already rendered from a new one, and how
 * a bug report can say which event the screenshot was taken at.
 *
 * <p>Money is a decimal string, never a JSON number -- the same rule every view in this app
 * follows.
 */
public record DepthData(String symbol,
                        long lastSequence,
                        List<Level> bids,
                        List<Level> asks) {

    /** One price level: everything resting at one price, on one side. */
    public record Level(String price, String quantity) {

        static Level of(DepthLevel level) {
            return new Level(Money.toDecimal(level.price()), Money.toDecimal(level.quantity()));
        }
    }

    public static DepthData of(Depth depth) {
        return new DepthData(depth.symbol(), depth.lastSequence(),
                depth.bids().stream().map(Level::of).toList(),
                depth.asks().stream().map(Level::of).toList());
    }

    /** A market with nothing resting on it -- an empty ladder rather than no frame at all,
     *  so a subscriber to a newly listed symbol sees an empty book instead of a spinner. */
    public static DepthData empty(String symbol) {
        return new DepthData(symbol, 0L, List.of(), List.of());
    }
}
