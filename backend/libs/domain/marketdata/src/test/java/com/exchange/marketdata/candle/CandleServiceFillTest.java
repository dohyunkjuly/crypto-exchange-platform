package com.exchange.marketdata.candle;

import static com.exchange.marketdata.candle.CandleFixture.candle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Gaps are filled at read time, never stored -- see CandleBar. */
@ExtendWith(MockitoExtension.class)
class CandleServiceFillTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");
    private static final CandleInterval M1 = CandleInterval.ONE_MINUTE;

    @Mock
    private CandleRepository repo;

    private Candle at(int minute, long open, long close) {
        return candle(Market.SPOT, "BTCUSDT", M1, T0.plusSeconds(60L * minute), open, Math.max(open, close), Math.min(open, close), close, 1, 1, 1);
    }

    /** The repository answers newest first, as it does in production, honouring the bound. */
    private void stored(Candle... newestFirst) {
        when(repo.findLatestUpTo(eq(Market.SPOT), eq("BTCUSDT"), eq(M1), any(), anyInt())).thenAnswer(inv -> {
            Instant upTo = inv.getArgument(3); int limit = inv.getArgument(4);
            return List.of(newestFirst).stream().filter(c -> !c.getOpenTime().isAfter(upTo)).limit(limit).toList();
        });
    }

    @Test
    void fillsEveryQuietBucketWithAFlatBarAtThePreviousClose() {
        stored(at(3, 105, 110), at(0, 100, 105));

        List<CandleBar> bars = new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 100, T0.plusSeconds(60L * 4 + 30));

        assertThat(bars).extracting(b -> b.openTime().getEpochSecond() - T0.getEpochSecond()).containsExactly(0L, 60L, 120L, 180L, 240L);
        CandleBar quiet = bars.get(1);
        assertThat(List.of(quiet.open(), quiet.high(), quiet.low(), quiet.close())).containsOnly(105L);
        assertThat(quiet.volume()).isZero();
        assertThat(bars.get(3).close()).isEqualTo(110);
        // The bucket containing "now" is present too, flat at the latest close.
        assertThat(bars.get(4).close()).isEqualTo(110);
    }

    @Test
    void neverInventsAPriceBeforeTheFirstTrade() {
        stored(at(5, 100, 100));
        List<CandleBar> bars = new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 100, T0.plusSeconds(60L * 6));
        assertThat(bars.get(0).openTime()).isEqualTo(T0.plusSeconds(300));
        assertThat(bars).hasSize(2);
    }

    /** Paging back: the page ending just before the oldest shown bar fills the same way, and a
     *  short page marks the start of history. */
    @Test
    void pagesBackFromAnEndTimeWithTheSameFill() {
        stored(at(6, 110, 111), at(3, 105, 110), at(0, 100, 105));

        List<CandleBar> page = new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 3, T0.plusSeconds(60L * 5));
        assertThat(page).extracting(b -> b.openTime().getEpochSecond() - T0.getEpochSecond()).containsExactly(180L, 240L, 300L);
        assertThat(page.get(1).close()).isEqualTo(110); // flat at minute 3's close, not minute 6's

        List<CandleBar> older = new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 3, T0.plusSeconds(60L * 2));
        assertThat(older).extracting(b -> b.openTime().getEpochSecond() - T0.getEpochSecond()).containsExactly(0L, 60L, 120L);

        assertThat(new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 3, T0.minusSeconds(60))).isEmpty();
    }

    @Test
    void emptyUntilTheMarketHasTraded() {
        stored();
        assertThat(new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 100, T0)).isEmpty();
    }

    @Test
    void returnsAtMostLimitBarsAndTheyAreTheNewest() {
        stored(at(0, 100, 100));
        List<CandleBar> bars = new CandleService(repo).latestFilled(Market.SPOT, "BTCUSDT", M1, 10, T0.plusSeconds(60L * 500));
        assertThat(bars).hasSize(10);
        assertThat(bars.get(9).openTime()).isEqualTo(T0.plusSeconds(60L * 500));
        assertThat(bars).allSatisfy(b -> assertThat(b.close()).isEqualTo(100));
    }
}
