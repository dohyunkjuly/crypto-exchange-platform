package com.exchange.marketdata.depth;

import static com.exchange.marketdata.depth.DepthBookFixture.levels;
import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.marketdata.candle.Market;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shaping between what MongoDB stores and what a caller gets: sort, filter, slice.
 *
 * <p>Everything here is pure -- no Mongo, no Spring. The stored map is deliberately built
 * in an order that is neither price order nor its reverse, so an implementation that
 * returned levels in encounter order would fail rather than pass by luck.
 */
class DepthTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final long ONE = 100_000_000L;

    /** 1e8-scaled, as a map key. {@code price(90)} is {@code "9000000000"}. */
    private static String price(long whole) {
        return String.valueOf(whole * ONE);
    }

    private static long scaled(long whole) {
        return whole * ONE;
    }

    /** A map key from an already-scaled price. {@link #price} multiplies a whole number;
     *  this takes the scaled value, for the fractional prices grouping is all about. */
    private static String at(long scaledPrice) {
        return String.valueOf(scaledPrice);
    }

    @Test
    void fullSortsBidsHighestFirstAndAsksLowestFirst() {
        Map<String, Long> bids = levels(price(99), ONE, price(101), 2 * ONE, price(100), 3 * ONE);
        Map<String, Long> asks = levels(price(105), ONE, price(103), 2 * ONE, price(104), 3 * ONE);

        Depth depth = Depth.ofFull(DepthBookFixture.full(Market.SPOT, SYMBOL, 42, bids, asks), 10);

        assertThat(depth.bids()).containsExactly(
                new DepthLevel(scaled(101), 2 * ONE),
                new DepthLevel(scaled(100), 3 * ONE),
                new DepthLevel(scaled(99), ONE));
        assertThat(depth.asks()).containsExactly(
                new DepthLevel(scaled(103), 2 * ONE),
                new DepthLevel(scaled(104), 3 * ONE),
                new DepthLevel(scaled(105), ONE));
        assertThat(depth.symbol()).isEqualTo(SYMBOL);
        assertThat(depth.lastSequence()).isEqualTo(42);
    }

    /**
     * The trap the map-key format sets. Keys are strings, and {@code "10000000000"} sorts
     * BEFORE {@code "9000000000"} lexicographically while being the larger number. Sorting
     * the keys as text would put 90 at the top of the bids and 100 at the top of the asks
     * -- an inverted book, reported with no error at all.
     */
    @Test
    void fullOrdersByNumericPriceNotByKeyText() {
        Map<String, Long> bids = levels(price(9), ONE, price(100), 2 * ONE, price(90), 3 * ONE);
        Map<String, Long> asks = levels(price(900), ONE, price(1000), 2 * ONE, price(90), 3 * ONE);

        Depth depth = Depth.ofFull(DepthBookFixture.full(Market.SPOT, SYMBOL, 1, bids, asks), 10);

        assertThat(depth.bids()).extracting(DepthLevel::price)
                .containsExactly(scaled(100), scaled(90), scaled(9));
        assertThat(depth.asks()).extracting(DepthLevel::price)
                .containsExactly(scaled(90), scaled(900), scaled(1000));
    }

    /** The limit applies after sorting: asking for one level must give the BEST one, not
     *  whichever the map happened to yield first. */
    @Test
    void fullAppliesTheLimitToTheBestLevelsNotTheFirstEncountered() {
        Map<String, Long> bids = levels(price(99), ONE, price(101), 2 * ONE, price(100), 3 * ONE);
        Map<String, Long> asks = levels(price(105), ONE, price(103), 2 * ONE);

        Depth depth = Depth.ofFull(DepthBookFixture.full(Market.SPOT, SYMBOL, 1, bids, asks), 1);

        assertThat(depth.bids()).containsExactly(new DepthLevel(scaled(101), 2 * ONE));
        assertThat(depth.asks()).containsExactly(new DepthLevel(scaled(103), 2 * ONE));
    }

    /** The writer deletes an emptied level, so a zero here means the fold is broken.
     *  Rendering it would show a price with no size behind it as if it were liquidity. */
    @Test
    void fullDropsLevelsWithNothingRestingOnThem() {
        Map<String, Long> bids = levels(price(101), 0L, price(100), 2 * ONE, price(99), -ONE);

        Depth depth = Depth.ofFull(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 1, bids, Map.of()), 10);

        assertThat(depth.bids()).containsExactly(new DepthLevel(scaled(100), 2 * ONE));
        assertThat(depth.asks()).isEmpty();
    }

    /**
     * Several levels collapsing into one row, which is the whole point. The quantities must
     * be summed rather than the last one winning -- a bucket that reported only its final
     * level would show a fraction of the liquidity actually resting there.
     */
    @Test
    void groupingSumsEveryLevelThatFallsInABucket() {
        Map<String, Long> bids = levels(
                at(scaled(101) + 70_000_000L), ONE,       // 101.7
                at(scaled(101) + 20_000_000L), 2 * ONE,   // 101.2  -> both bucket to 101
                price(100), 3 * ONE);
        Map<String, Long> asks = levels(
                at(scaled(103) + 10_000_000L), ONE,       // 103.1
                at(scaled(103) + 90_000_000L), 2 * ONE,   // 103.9  -> both bucket to 104
                price(105), 3 * ONE);

        Depth depth = Depth.grouped(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 5, bids, asks), scaled(1), 10);

        assertThat(depth.bids()).containsExactly(
                new DepthLevel(scaled(101), 3 * ONE),   // 1 + 2, not 2
                new DepthLevel(scaled(100), 3 * ONE));
        assertThat(depth.asks()).containsExactly(
                new DepthLevel(scaled(104), 3 * ONE),   // 1 + 2, rounded up
                new DepthLevel(scaled(105), 3 * ONE));
    }

    /** The limit counts buckets, not levels. Applying it before grouping would return
     *  fewer rows than asked for whenever levels happened to share a bucket. */
    @Test
    void theLimitCountsBucketsRatherThanUnderlyingLevels() {
        Map<String, Long> bids = levels(
                at(scaled(101) + 70_000_000L), ONE,
                at(scaled(101) + 20_000_000L), ONE,
                at(scaled(100) + 50_000_000L), ONE,
                price(99), ONE);

        Depth depth = Depth.grouped(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 5, bids, Map.of()), scaled(1), 2);

        assertThat(depth.bids()).containsExactly(
                new DepthLevel(scaled(101), 2 * ONE),
                new DepthLevel(scaled(100), ONE));
    }

    @Test
    void groupingKeepsBestBucketFirstOnBothSides() {
        Map<String, Long> bids = levels(price(99), ONE, price(101), ONE, price(100), ONE);
        Map<String, Long> asks = levels(price(105), ONE, price(103), ONE, price(104), ONE);

        Depth depth = Depth.grouped(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 5, bids, asks), scaled(1), 10);

        assertThat(depth.bids()).extracting(DepthLevel::price)
                .containsExactly(scaled(101), scaled(100), scaled(99));
        assertThat(depth.asks()).extracting(DepthLevel::price)
                .containsExactly(scaled(103), scaled(104), scaled(105));
    }

    @Test
    void groupingDropsLevelsWithNothingRestingOnThem() {
        Map<String, Long> bids = levels(price(101), 0L, price(100), 2 * ONE);

        Depth depth = Depth.grouped(
                DepthBookFixture.full(Market.SPOT, SYMBOL, 5, bids, Map.of()), scaled(1), 10);

        assertThat(depth.bids()).containsExactly(new DepthLevel(scaled(100), 2 * ONE));
    }

    @Test
    void topTrimsThePrecomputedArraysWithoutReordering() {
        List<DepthLevel> topBids = List.of(
                new DepthLevel(scaled(101), ONE),
                new DepthLevel(scaled(100), 2 * ONE),
                new DepthLevel(scaled(99), 3 * ONE));
        List<DepthLevel> topAsks = List.of(
                new DepthLevel(scaled(103), ONE),
                new DepthLevel(scaled(104), 2 * ONE));

        Depth depth = Depth.ofTop(
                DepthBookFixture.top(Market.SPOT, SYMBOL, 7, topBids, topAsks), 2);

        assertThat(depth.bids()).containsExactly(topBids.get(0), topBids.get(1));
        assertThat(depth.asks()).containsExactly(topAsks.get(0), topAsks.get(1));
        assertThat(depth.lastSequence()).isEqualTo(7);
    }

    @Test
    void topReturnsEverythingWhenTheLimitExceedsWhatIsStored() {
        List<DepthLevel> topBids = List.of(new DepthLevel(scaled(101), ONE));

        Depth depth = Depth.ofTop(
                DepthBookFixture.top(Market.SPOT, SYMBOL, 7, topBids, List.of()), 50);

        assertThat(depth.bids()).isEqualTo(topBids);
        assertThat(depth.asks()).isEmpty();
    }

    /**
     * A one-sided book stores no field at all for the empty side, and a projected read
     * fetches only some fields by design -- so nulls arrive here through both normal paths.
     * Neither is an error; both mean "nothing on this side".
     */
    @Test
    void missingSidesReadAsEmptyRatherThanFailing() {
        DepthBook noArrays = DepthBookFixture.top(Market.SPOT, SYMBOL, 3, null, null);
        DepthBook noLadder = DepthBookFixture.full(Market.SPOT, SYMBOL, 3, null, null);

        assertThat(Depth.ofTop(noArrays, 20).bids()).isEmpty();
        assertThat(Depth.ofTop(noArrays, 20).asks()).isEmpty();
        assertThat(Depth.ofFull(noLadder, 20).bids()).isEmpty();
        assertThat(Depth.ofFull(noLadder, 20).asks()).isEmpty();
    }
}
