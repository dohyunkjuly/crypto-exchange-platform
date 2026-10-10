package com.exchange.marketdata.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ingest.CandleBackfill.Bucket;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

class CandleBackfillTest {

    private static final Instant T0 = Instant.parse("2026-01-31T22:00:00Z");

    /** Populated the way the Mongo mapper does it; the entity has no public constructor or setters. */
    private static Candle minute(int offset, long open, long high, long low, long close, long volume, long seq) {
        Candle c = BeanUtils.instantiateClass(Candle.class);
        Instant openTime = T0.plusSeconds(60L * offset);
        ReflectionTestUtils.setField(c, "id", Candle.idOf(Market.SPOT, "BTCUSDT", CandleInterval.ONE_MINUTE, openTime));
        ReflectionTestUtils.setField(c, "openTime", openTime);
        ReflectionTestUtils.setField(c, "open", open);
        ReflectionTestUtils.setField(c, "high", high);
        ReflectionTestUtils.setField(c, "low", low);
        ReflectionTestUtils.setField(c, "close", close);
        ReflectionTestUtils.setField(c, "volume", volume);
        ReflectionTestUtils.setField(c, "quoteVolume", volume * close);
        ReflectionTestUtils.setField(c, "tradeCount", 1);
        ReflectionTestUtils.setField(c, "lastSequence", seq);
        return c;
    }

    @Test
    @DisplayName("a rolled-up bucket opens at its first minute, closes at its last, and sums the rest")
    void rollsMinutesIntoTheIntervalsBuckets() {
        List<Bucket> hours = CandleBackfill.rollup(Stream.of(
                minute(0, 100, 110, 90, 105, 1, 7),
                minute(30, 105, 120, 100, 115, 2, 9),
                minute(60, 115, 116, 80, 90, 3, 11)), CandleInterval.ONE_HOUR);

        assertThat(hours).hasSize(2);
        Bucket first = hours.get(0);
        assertThat(first.openTime()).isEqualTo(T0);
        assertThat(List.of(first.open(), first.high(), first.low(), first.close())).containsExactly(100L, 120L, 90L, 115L);
        assertThat(first.volume()).isEqualTo(3);
        assertThat(first.tradeCount()).isEqualTo(2);
        assertThat(first.lastSequence()).isEqualTo(9);
        assertThat(hours.get(1).openTime()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    @DisplayName("a month bucket follows the calendar, so minutes either side of midnight on the 1st split")
    void monthBucketsSplitAtTheCalendarBoundary() {
        List<Bucket> months = CandleBackfill.rollup(Stream.of(
                minute(0, 100, 100, 100, 100, 1, 1),      // Jan 31 22:00
                minute(150, 100, 100, 100, 100, 1, 2)),   // Feb 1 00:30
                CandleInterval.ONE_MONTH);
        assertThat(months).extracting(Bucket::openTime)
                .containsExactly(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void nothingToRollUpIsNoBuckets() {
        assertThat(CandleBackfill.rollup(Stream.empty(), CandleInterval.FOUR_HOURS)).isEmpty();
    }
}
