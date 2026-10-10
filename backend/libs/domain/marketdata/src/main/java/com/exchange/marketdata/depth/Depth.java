package com.exchange.marketdata.depth;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A snapshot of one book, sliced to the depth a caller asked for.
 *
 * <p>What {@link DepthService} returns, as opposed to {@link DepthBook}, which is what
 * MongoDB stores. The difference is the point: a stored book keeps every level in an
 * unordered map, while a caller wants the best N of each side in price order and has no
 * use for the storage shape. Same relationship {@link com.exchange.marketdata.ticker.Ticker}
 * has to candles.
 *
 * <p><b>{@code lastSequence} is not decoration.</b> It is the event sequence this snapshot
 * reflects, and a client streaming diffs on top of it needs it to know which diffs it has
 * already got -- see {@code events.proto} on why sequences are dense. Dropping it from a
 * response would leave a client no way to tell a correct book from a silently wrong one.
 *
 * <p>Prices and quantities are 1e8-scaled longs throughout.
 */
public record Depth(String symbol,
                    List<DepthLevel> bids,
                    List<DepthLevel> asks,
                    long lastSequence) {

    /** Highest bid first: the best bid is the most someone will pay. */
    private static final Comparator<DepthLevel> BEST_BID_FIRST =
            Comparator.comparingLong(DepthLevel::price).reversed();

    /** Lowest ask first: the best ask is the least someone will take. */
    private static final Comparator<DepthLevel> BEST_ASK_FIRST =
            Comparator.comparingLong(DepthLevel::price);

    /**
     * From the writer's precomputed arrays -- already sorted and already capped at {@link
     * DepthBook#TOP_LEVELS}, so this only trims to the requested size.
     */
    static Depth ofTop(DepthBook book, int limit) {
        return new Depth(book.getSymbol(),
                trim(book.getTopBids(), limit),
                trim(book.getTopAsks(), limit),
                book.getLastSequence());
    }

    /**
     * The book collapsed into buckets {@code grouping} wide, best bucket first.
     *
     * <p>Reads the full ladder, and has to: grouping is a question about a <em>price
     * range</em>, not a level count. Twenty rows at a grouping of 100 span 2,000 of price,
     * which on a market that ticks in 0.01 is most of the book -- the precomputed top of
     * book could not fill a single row of it.
     */
    static Depth grouped(DepthBook book, long grouping, int limit) {
        return new Depth(book.getSymbol(),
                bucketed(book.getBids(), BookSide.BID, grouping, BEST_BID_FIRST, limit),
                bucketed(book.getAsks(), BookSide.ASK, grouping, BEST_ASK_FIRST, limit),
                book.getLastSequence());
    }

    /**
     * From the full ladder, for a caller asking past {@link DepthBook#TOP_LEVELS}. Sorts
     * every level to find the best {@code limit} of each side, which is the cost that
     * makes this the fallback rather than the default path.
     */
    static Depth ofFull(DepthBook book, int limit) {
        return new Depth(book.getSymbol(),
                sorted(book.getBids(), BEST_BID_FIRST, limit),
                sorted(book.getAsks(), BEST_ASK_FIRST, limit),
                book.getLastSequence());
    }

    /**
     * Null-tolerant because a book with no asks stores no {@code asks} field at all, and a
     * projected read deliberately fetches only some of these fields. Neither is an error;
     * both mean "nothing to show on this side".
     */
    private static List<DepthLevel> trim(List<DepthLevel> levels, int limit) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.size() <= limit ? List.copyOf(levels) : List.copyOf(levels.subList(0, limit));
    }

    /** One pass to bucket and sum, one sort over the buckets. Summing before sorting is
     *  what keeps this proportional to the number of distinct prices rather than to the
     *  number of rows returned times the levels behind each. */
    private static List<DepthLevel> bucketed(Map<String, Long> levels, BookSide side,
                                             long grouping, Comparator<DepthLevel> order,
                                             int limit) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> buckets = new java.util.HashMap<>();
        levels.forEach((price, quantity) -> {
            if (quantity == null || quantity <= 0) {
                return; // see sorted() -- a level the writer should already have removed
            }
            buckets.merge(PriceGrouping.bucketOf(Long.parseLong(price), grouping, side),
                    quantity, Long::sum);
        });
        return buckets.entrySet().stream()
                .map(bucket -> new DepthLevel(bucket.getKey(), bucket.getValue()))
                .sorted(order)
                .limit(limit)
                .toList();
    }

    private static List<DepthLevel> sorted(Map<String, Long> levels,
                                           Comparator<DepthLevel> order, int limit) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        return levels.entrySet().stream()
                // Defensive, not expected: the writer deletes a level once it empties, so a
                // zero or negative quantity here is a bug in the fold. Skipping it shows up
                // as missing liquidity rather than a phantom level of size zero in the UI,
                // which is the failure that gets noticed rather than rendered.
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .map(entry -> new DepthLevel(Long.parseLong(entry.getKey()), entry.getValue()))
                .sorted(order)
                .limit(limit)
                .toList();
    }
}
