package com.exchange.marketdata.depth;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapsing a book's price levels into coarser buckets -- the {@code 0.01 / 0.1 / 1 / 10}
 * selector on a trading screen.
 *
 * <p>Pure arithmetic on 1e8-scaled longs, which is why it lives here rather than in either
 * app: what a bucket <em>is</em> is the same question for every caller. Which groupings a
 * given market offers is a different question -- it depends on that symbol's tick and
 * price, which live in {@code spot_symbols} -- and stays with whoever owns that.
 *
 * <p><b>Both sides round away from the spread.</b> A bid rounds down, an ask rounds up, so
 * the price shown for a bucket is always the <em>worst</em> price in it: sell into a
 * displayed bid and you get at least that, buy into a displayed ask and you pay at most
 * that. Rounding either one the other way would advertise a price nobody is actually
 * offering, and the two rows at the touch are exactly where someone would be looking.
 */
public final class PriceGrouping {

    /** How many decades of grouping to offer when a symbol has none configured. */
    private static final int DERIVED_DECADES = 4;

    private PriceGrouping() {
    }

    /**
     * The bucket {@code price} belongs to, named by its own worst price.
     *
     * @param grouping bucket width, 1e8-scaled and positive
     */
    public static long bucketOf(long price, long grouping, BookSide side) {
        if (grouping <= 0) {
            throw new IllegalArgumentException("grouping must be positive, was " + grouping);
        }
        long remainder = Math.floorMod(price, grouping);
        if (remainder == 0) {
            return price;
        }
        return side == BookSide.BID
                ? price - remainder                 // down, away from the spread
                : price - remainder + grouping;     // up, away from the spread
    }

    /**
     * What a market offers when nothing was configured for it: the tick and three decades
     * above it.
     *
     * <p>Deliberately crude, and deliberately not the whole answer. A useful coarse
     * grouping depends on <em>price</em>, not just tick -- BTC's tick is around 400 times
     * finer relative to its price than DOGE's, so no fixed multiplier ladder suits both.
     * This exists so a market listed without configuration still gets a working selector
     * rather than an empty one; the configured list is how a market gets a good one.
     */
    public static List<Long> derivedFrom(long priceTick) {
        if (priceTick <= 0) {
            throw new IllegalArgumentException("priceTick must be positive, was " + priceTick);
        }
        List<Long> groupings = new ArrayList<>(DERIVED_DECADES);
        long grouping = priceTick;
        for (int decade = 0; decade < DERIVED_DECADES; decade++) {
            groupings.add(grouping);
            grouping *= 10;
        }
        return List.copyOf(groupings);
    }

    /**
     * Whether every grouping is usable on a book that ticks in {@code priceTick}.
     *
     * <p>A grouping finer than the tick produces buckets no order can ever land in -- rows
     * that are always empty. One that is not a whole multiple puts bucket boundaries
     * mid-tick, so the same price falls in different buckets depending on where the ladder
     * happens to start. Neither fails loudly; both just render a subtly wrong book.
     */
    public static boolean areValidFor(List<Long> groupings, long priceTick) {
        if (groupings.isEmpty()) {
            return false;
        }
        long previous = 0;
        for (Long grouping : groupings) {
            if (grouping == null || grouping < priceTick || grouping % priceTick != 0) {
                return false;
            }
            // Ascending and distinct: the order a selector renders in, fixed here so no
            // caller has to sort and no two callers disagree about the order.
            if (grouping <= previous) {
                return false;
            }
            previous = grouping;
        }
        return true;
    }
}
