package com.exchange.bff.market.dto;

import com.exchange.common.money.Money;
import com.exchange.marketdata.depth.Depth;
import com.exchange.marketdata.depth.DepthLevel;
import java.util.List;

/**
 * One side-by-side snapshot of the order book, as the browser sees it.
 *
 * <p>Same rule as {@link CandleView}: money is a decimal string, never a JSON number. A
 * level is a 1e8-scaled long precisely so it never touches a float, and serialising it as
 * a JSON number would hand it straight to JavaScript's double and undo that at the last
 * step -- on the one screen where a wrong seventh decimal is somebody's order.
 *
 * <p><b>{@code lastSequence} is load-bearing, not diagnostic.</b> It says which event this
 * snapshot reflects, and a client streaming diffs on top of it uses it to discard the
 * updates already included here. Without it there is no way to join a snapshot to a
 * stream, and a client would have no way to tell a correct book from a silently wrong one.
 *
 * <p>Levels are named pairs rather than the positional {@code ["60000.00","1.5"]}
 * arrays. Every other view in this app is a named record, and the wire saving is not worth
 * being the one place a reader has to remember which slot is the price.
 */
public record DepthView(String symbol,
                        long lastSequence,
                        List<LevelView> bids,
                        List<LevelView> asks) {

    /** One price level: everything resting at one price, on one side. */
    public record LevelView(String price, String quantity) {

        static LevelView of(DepthLevel level) {
            return new LevelView(Money.toDecimal(level.price()),
                    Money.toDecimal(level.quantity()));
        }
    }

    public static DepthView of(Depth depth) {
        return new DepthView(depth.symbol(), depth.lastSequence(),
                depth.bids().stream().map(LevelView::of).toList(),
                depth.asks().stream().map(LevelView::of).toList());
    }

    /**
     * A book with nothing resting on it.
     *
     * <p>What a symbol that has never been quoted returns -- deliberately an empty ladder
     * rather than a 404. A newly listed market exists and is perfectly valid; it simply has
     * no orders yet, and the trading page should draw an empty book rather than send the
     * user looking for a symbol that is right there in the list.
     *
     * <p>{@code lastSequence} is 0, which is below every real sequence, so a client can
     * apply diffs on top of this without a special case.
     */
    public static DepthView empty(String symbol) {
        return new DepthView(symbol, 0L, List.of(), List.of());
    }
}
