package com.exchange.marketdata.candle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Bucketing is the one piece of arithmetic here that everything else trusts blindly -- a
 * candle in the wrong bucket is a wrong chart with no error anywhere -- so the boundaries
 * are pinned explicitly rather than assumed.
 */
class CandleIntervalTest {

    @Test
    void monthBucketsAreCalendarMonthsInUtc() {
        Instant at = Instant.parse("2026-02-17T13:47:05Z");
        assertThat(CandleInterval.ONE_MONTH.bucketStart(at)).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(CandleInterval.ONE_MONTH.next(Instant.parse("2026-02-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(CandleInterval.ONE_MONTH.previous(Instant.parse("2026-03-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        // Fixed-length intervals step by their length.
        assertThat(CandleInterval.EIGHT_HOURS.next(Instant.parse("2026-02-17T08:00:00Z"))).isEqualTo(Instant.parse("2026-02-17T16:00:00Z"));
        assertThat(CandleInterval.THIRTY_MINUTES.bucketStart(at)).isEqualTo(Instant.parse("2026-02-17T13:30:00Z"));
    }

    @Test
    void bucketStartFloorsToTheIntervalBoundary() {
        Instant at = Instant.parse("2026-09-05T14:37:42.913Z");

        assertThat(CandleInterval.ONE_MINUTE.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T14:37:00Z"));
        assertThat(CandleInterval.FIVE_MINUTES.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T14:35:00Z"));
        assertThat(CandleInterval.FIFTEEN_MINUTES.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T14:30:00Z"));
        assertThat(CandleInterval.ONE_HOUR.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T14:00:00Z"));
        assertThat(CandleInterval.FOUR_HOURS.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T12:00:00Z"));
        assertThat(CandleInterval.ONE_DAY.bucketStart(at))
                .isEqualTo(Instant.parse("2026-09-05T00:00:00Z"));
    }

    @Test
    void aTimeExactlyOnABoundaryOpensThatBucketRatherThanTheOneBefore() {
        Instant onBoundary = Instant.parse("2026-09-05T14:00:00Z");

        assertThat(CandleInterval.ONE_HOUR.bucketStart(onBoundary)).isEqualTo(onBoundary);
        assertThat(CandleInterval.ONE_MINUTE.bucketStart(onBoundary)).isEqualTo(onBoundary);
    }

    @Test
    void bucketsBeforeTheEpochStillFloorDownwards() {
        // Math.floorDiv, not integer division, is what makes this true -- plain division
        // truncates toward zero and would round a pre-epoch time UP into the next bucket.
        Instant beforeEpoch = Instant.parse("1969-12-31T23:59:30Z");

        assertThat(CandleInterval.ONE_MINUTE.bucketStart(beforeEpoch))
                .isEqualTo(Instant.parse("1969-12-31T23:59:00Z"));
    }

    @Test
    void ofCodeRoundTripsEveryInterval() {
        for (CandleInterval interval : CandleInterval.values()) {
            assertThat(CandleInterval.ofCode(interval.code())).isEqualTo(interval);
        }
    }

    @Test
    void ofCodeRejectsAnUnknownCode() {
        assertThatThrownBy(() -> CandleInterval.ofCode("3m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("3m");
    }
}
