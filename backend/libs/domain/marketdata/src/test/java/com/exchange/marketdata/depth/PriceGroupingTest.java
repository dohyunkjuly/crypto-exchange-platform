package com.exchange.marketdata.depth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bucketing arithmetic on its own. Every case here is chosen so that rounding the
 * wrong way gives a different answer -- prices that sit exactly on a boundary would pass
 * either way and prove nothing.
 */
class PriceGroupingTest {

    private static final long ONE = 100_000_000L;
    private static final long CENT = ONE / 100;

    /**
     * The rule the whole class turns on. A bid rounds DOWN, so the row says the worst
     * price in it -- sell into a displayed bid and you get at least that. Rounding up
     * would advertise a bid nobody has actually placed, at the touch, where it is exactly
     * the number a trader is reading.
     */
    @Test
    void aBidRoundsDownAwayFromTheSpread() {
        assertThat(PriceGrouping.bucketOf(60_000_70000000L, ONE, BookSide.BID))
                .isEqualTo(60_000_00000000L);
        assertThat(PriceGrouping.bucketOf(60_000_99999999L, ONE, BookSide.BID))
                .isEqualTo(60_000_00000000L);
    }

    /** And an ask rounds UP: buy into a displayed ask and you pay at most that. */
    @Test
    void anAskRoundsUpAwayFromTheSpread() {
        assertThat(PriceGrouping.bucketOf(60_001_30000000L, ONE, BookSide.ASK))
                .isEqualTo(60_002_00000000L);
        assertThat(PriceGrouping.bucketOf(60_001_00000001L, ONE, BookSide.ASK))
                .isEqualTo(60_002_00000000L);
    }

    /** A price already on a boundary is its own bucket on both sides -- rounding it away
     *  would shift a whole ladder by one row. */
    @Test
    void aPriceOnABoundaryDoesNotMove() {
        assertThat(PriceGrouping.bucketOf(60_000_00000000L, ONE, BookSide.BID))
                .isEqualTo(60_000_00000000L);
        assertThat(PriceGrouping.bucketOf(60_000_00000000L, ONE, BookSide.ASK))
                .isEqualTo(60_000_00000000L);
    }

    /** Grouping at the tick is the ungrouped book: every level keeps its own price. */
    @Test
    void groupingAtTheTickChangesNothing() {
        assertThat(PriceGrouping.bucketOf(60_000_37000000L, CENT, BookSide.BID))
                .isEqualTo(60_000_37000000L);
        assertThat(PriceGrouping.bucketOf(60_000_37000000L, CENT, BookSide.ASK))
                .isEqualTo(60_000_37000000L);
    }

    /** Sub-cent prices are the common case on the cheap markets, and the one where an
     *  integer-division mistake would silently collapse everything into bucket zero. */
    @Test
    void worksAtTheScaleOfACheapAsset() {
        long tenthOfACent = ONE / 1000;                 // 0.001
        assertThat(PriceGrouping.bucketOf(15_000_000L, tenthOfACent, BookSide.BID))
                .isEqualTo(15_000_000L);                // 0.15 exactly
        assertThat(PriceGrouping.bucketOf(15_050_000L, tenthOfACent, BookSide.BID))
                .isEqualTo(15_000_000L);                // 0.1505 -> 0.15
        assertThat(PriceGrouping.bucketOf(15_050_000L, tenthOfACent, BookSide.ASK))
                .isEqualTo(15_100_000L);                // 0.1505 -> 0.151
    }

    @Test
    void aNonPositiveGroupingIsTheCallersMistake() {
        assertThatThrownBy(() -> PriceGrouping.bucketOf(ONE, 0, BookSide.BID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PriceGrouping.bucketOf(ONE, -1, BookSide.BID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivedGroupingsStartAtTheTickAndClimbByDecades() {
        assertThat(PriceGrouping.derivedFrom(CENT))
                .containsExactly(CENT, 10 * CENT, 100 * CENT, 1000 * CENT);
    }

    /** A grouping finer than the tick makes rows no order can land in; one that is not a
     *  whole multiple puts boundaries mid-tick, so the same price buckets differently
     *  depending on where the ladder starts. Neither fails loudly. */
    @Test
    void rejectsGroupingsThatAreNotWholeMultiplesOfTheTick() {
        assertThat(PriceGrouping.areValidFor(List.of(CENT, 10 * CENT), CENT)).isTrue();
        assertThat(PriceGrouping.areValidFor(List.of(CENT / 2), CENT)).isFalse();
        assertThat(PriceGrouping.areValidFor(List.of(CENT + 1), CENT)).isFalse();
        assertThat(PriceGrouping.areValidFor(List.of(), CENT)).isFalse();
    }

    /** Ascending and distinct, so the selector renders in order without anyone sorting it
     *  and two callers cannot disagree about that order. */
    @Test
    void rejectsGroupingsThatAreNotAscending() {
        assertThat(PriceGrouping.areValidFor(List.of(10 * CENT, CENT), CENT)).isFalse();
        assertThat(PriceGrouping.areValidFor(List.of(CENT, CENT), CENT)).isFalse();
    }
}
