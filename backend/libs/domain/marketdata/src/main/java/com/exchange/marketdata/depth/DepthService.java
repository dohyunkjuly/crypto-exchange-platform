package com.exchange.marketdata.depth;

import com.exchange.marketdata.candle.Market;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reading order books. Shared by web-bff (the trading page's ladder) and spot-open-api
 * (its public {@code /depth}), which is why it lives here rather than in either app -- see
 * AGENTS.md's "does more than one app need it?".
 *
 * <p>One method, two storage paths. {@link DepthBook} keeps the same book as both a
 * precomputed top-of-book and a full ladder; which one answers a given request is this
 * class's decision and nobody else's. Callers ask for a depth and get it -- pushing the
 * choice out to them would mean every caller has to know {@link DepthBook#TOP_LEVELS},
 * and the day that constant changes, the ones that guessed wrong quietly start reading
 * the expensive way.
 */
@Service
public class DepthService {

    /**
     * A cap, not a default. A REST snapshot stops around 5000 levels for the same
     * reason: past this the response is larger than any client can act on, and asking for
     * "everything" on a busy market is a request big enough to hurt both ends.
     */
    public static final int MAX_LEVELS = 5_000;

    private final DepthRepository books;

    public DepthService(DepthRepository books) {
        this.books = books;
    }

    /**
     * The best {@code limit} buckets of each side once the book is collapsed into groups
     * {@code grouping} wide, or empty if this symbol has no book yet.
     *
     * <p>Always reads the full ladder, unlike {@link #of}, and cannot do otherwise:
     * grouping asks about a price range rather than a level count, so the precomputed top
     * of book is the wrong shape however deep it goes. A caller pushing these on a timer
     * should read once and build every grouping from that one book rather than calling this
     * per grouping.
     *
     * @throws IllegalArgumentException if {@code limit} is not positive or {@code grouping}
     *         is not positive.
     */
    public Optional<Depth> grouped(Market market, String symbol, long grouping, int limit) {
        return grouped(market, symbol, List.of(grouping), limit)
                .map(byGrouping -> byGrouping.get(grouping));
    }

    /**
     * Every requested grouping of one book, from a <b>single</b> read.
     *
     * <p>The method a publisher on a timer should call. Grouping the same book four ways is
     * four walks over a list already in memory; fetching it four times is four reads of the
     * largest document this system stores. One market watched at several groupings at once
     * is the normal case on a trading screen, not an edge case.
     *
     * @return the depths by grouping, or empty if this symbol has no book yet -- so a
     *         caller can tell "nothing resting" from "no book" without a second call.
     */
    public Optional<Map<Long, Depth>> grouped(Market market, String symbol,
                                              Collection<Long> groupings, int limit) {
        return views(market, symbol, groupings, false, limit).map(Views::grouped);
    }

    /**
     * Every grouping <b>and</b> the ungrouped ladder, from one read.
     *
     * <p>For a publisher serving one market to subscribers who did not all pick the same
     * bucket size -- which is the normal case the moment a grouping selector exists.
     *
     * <p>Two reads would not merely cost twice as much: {@code market-data} writes this
     * document continuously, so a second fetch can land the other side of a write, and the
     * views would then describe two different books. Everything derived here comes from one
     * document, so every view shares one {@code lastSequence} because it is genuinely the
     * same book.
     *
     * @param includeUngrouped whether anyone is watching the raw ladder as well
     */
    public Optional<Views> views(Market market, String symbol, Collection<Long> groupings,
                                 boolean includeUngrouped, int limit) {
        int levels = clamp(limit);
        return books.findFull(market, symbol).map(book -> {
            Map<Long, Depth> byGrouping = new LinkedHashMap<>();
            for (Long grouping : groupings) {
                byGrouping.put(grouping, Depth.grouped(book, grouping, levels));
            }
            return new Views(Map.copyOf(byGrouping),
                    includeUngrouped ? Depth.ofFull(book, levels) : null);
        });
    }

    /**
     * One book, seen several ways.
     *
     * @param ungrouped the raw ladder, or null when nobody asked for it. Null rather than
     *                  an empty {@link Depth}, because "nobody is watching this" and "the
     *                  book is empty" are different things and a caller must not broadcast
     *                  the second when it meant the first.
     */
    public record Views(Map<Long, Depth> grouped, Depth ungrouped) {

        /** Whichever view exists, purely to read the sequence they all share. */
        public Depth any() {
            return ungrouped != null
                    ? ungrouped
                    : grouped.values().stream().findFirst().orElse(null);
        }
    }

    /**
     * The best {@code limit} levels of each side, best price first, or empty if this
     * symbol has no book yet.
     *
     * <p>Empty means "nothing has ever rested here" -- a newly listed market, or one whose
     * book the writer has not reached yet. It is not an error, and a caller should render
     * an empty ladder rather than treat the symbol as unknown.
     *
     * @throws IllegalArgumentException if {@code limit} is not positive. A plain unchecked
     *         exception with no error code, per AGENTS.md: a shared domain service
     *         cannot reference either app's {@code ErrorCode}, so translating this into a
     *         response is each app's own job.
     */
    public Optional<Depth> of(Market market, String symbol, int limit) {
        int levels = clamp(limit);
        return levels <= DepthBook.TOP_LEVELS
                ? books.findTop(market, symbol).map(book -> Depth.ofTop(book, levels))
                : books.findFull(market, symbol).map(book -> Depth.ofFull(book, levels));
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        return Math.min(limit, MAX_LEVELS);
    }
}
