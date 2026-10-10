package com.exchange.marketdata.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleFixture;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.CandleRepository;
import com.exchange.marketdata.candle.Market;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The repository is mocked: what is under test is the arithmetic {@link TickerService}
 * does over candles it is handed, and which of its two paths it takes. Whether Mongo
 * returns the right range is {@link CandleRepository}'s problem.
 *
 * <p>The clock is fixed, which is the whole reason {@code TickerService} has a
 * package-private second constructor -- a rolling 24h window is otherwise a different
 * answer every second.
 */
@ExtendWith(MockitoExtension.class)
class TickerServiceTest {

    private static final String SYMBOL = "BTCUSDT";

    /** Deliberately mid-hour: the window is supposed to snap to bucket edges, and a
     *  timestamp at :00:00 would hide a failure to do so. */
    private static final Instant NOW = Instant.parse("2026-09-05T14:37:42Z");

    private static final long ONE = 100_000_000L;

    @Mock
    private CandleRepository candles;

    private TickerService service() {
        return new TickerService(candles, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void summarisesTheWholeWindowRatherThanReadingTheNewestCandle() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                //         openTime          open       high        low       close   vol  quoteVol trades
                hourly(NOW.minusSeconds(7200), 100 * ONE, 120 * ONE,  90 * ONE, 110 * ONE, 5 * ONE,  550 * ONE, 3),
                hourly(NOW.minusSeconds(3600), 110 * ONE, 115 * ONE,  80 * ONE, 105 * ONE, 2 * ONE,  210 * ONE, 1),
                hourly(NOW,                    105 * ONE, 130 * ONE, 100 * ONE, 125 * ONE, 1 * ONE,  125 * ONE, 4)));

        Ticker ticker = service().of(Market.SPOT, SYMBOL).orElseThrow();

        // Open is the FIRST bucket's open and last price the LAST bucket's close -- not
        // either one's own open/close, which is the easy mistake here.
        assertThat(ticker.openPrice()).isEqualTo(100 * ONE);
        assertThat(ticker.lastPrice()).isEqualTo(125 * ONE);
        // High and low span every bucket, including ones that are neither first nor last.
        assertThat(ticker.highPrice()).isEqualTo(130 * ONE);
        assertThat(ticker.lowPrice()).isEqualTo(80 * ONE);
        assertThat(ticker.volume()).isEqualTo(8 * ONE);
        assertThat(ticker.quoteVolume()).isEqualTo(885 * ONE);
        assertThat(ticker.tradeCount()).isEqualTo(8);
        assertThat(ticker.priceChange()).isEqualTo(25 * ONE);
        // 25/100 = +25.00%
        assertThat(ticker.priceChangeBps()).isEqualTo(2_500);
    }

    @Test
    void reportsAFallAsNegativeChangeAndNegativeBasisPoints() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                hourly(NOW.minusSeconds(3600), 200 * ONE, 200 * ONE, 150 * ONE, 180 * ONE, ONE, 180 * ONE, 1),
                hourly(NOW,                    180 * ONE, 185 * ONE, 150 * ONE, 150 * ONE, ONE, 150 * ONE, 1)));

        Ticker ticker = service().of(Market.SPOT, SYMBOL).orElseThrow();

        assertThat(ticker.priceChange()).isEqualTo(-50 * ONE);
        assertThat(ticker.priceChangeBps()).isEqualTo(-2_500);
    }

    /** A window that ends on the current bucket's edge and covers 24 whole hours back --
     *  so 14:37 reports through 15:00 today, from 15:00 yesterday. */
    @Test
    void snapsTheWindowToWholeHours() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                hourly(NOW, 100 * ONE, 100 * ONE, 100 * ONE, 100 * ONE, ONE, 100 * ONE, 1)));

        Ticker ticker = service().of(Market.SPOT, SYMBOL).orElseThrow();

        assertThat(ticker.windowEnd()).isEqualTo(Instant.parse("2026-09-05T15:00:00Z"));
        assertThat(ticker.windowStart()).isEqualTo(Instant.parse("2026-09-04T15:00:00Z"));
    }

    /**
     * A symbol that has not traded today still has a price. Reporting it as absent would
     * make an illiquid market look delisted; reporting the stale close with honest zero
     * volume is what the window actually says happened.
     */
    @Test
    void fallsBackToTheLastKnownCloseWhenNothingTradedInTheWindow() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(candles.findMostRecent(Market.SPOT, SYMBOL, CandleInterval.ONE_HOUR))
                .thenReturn(Optional.of(hourly(NOW.minusSeconds(86_400 * 30),
                        90 * ONE, 95 * ONE, 85 * ONE, 88 * ONE, 7 * ONE, 616 * ONE, 9)));

        Ticker ticker = service().of(Market.SPOT, SYMBOL).orElseThrow();

        // Every price is that close, so a chart draws a flat line rather than a hole...
        assertThat(ticker.lastPrice()).isEqualTo(88 * ONE);
        assertThat(ticker.openPrice()).isEqualTo(88 * ONE);
        assertThat(ticker.highPrice()).isEqualTo(88 * ONE);
        assertThat(ticker.lowPrice()).isEqualTo(88 * ONE);
        // ...but the old bucket's volume must NOT leak into a window it is not inside.
        assertThat(ticker.volume()).isZero();
        assertThat(ticker.quoteVolume()).isZero();
        assertThat(ticker.tradeCount()).isZero();
        assertThat(ticker.priceChange()).isZero();
        assertThat(ticker.priceChangeBps()).isZero();
        // The window it reports is still the current one, not the stale candle's.
        assertThat(ticker.windowEnd()).isEqualTo(Instant.parse("2026-09-05T15:00:00Z"));
    }

    /** Never traded at all is a different answer from "quiet lately", and only this one
     *  is absent -- the caller turns it into a 404. */
    @Test
    void isEmptyForASymbolThatHasNeverTraded() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of());
        when(candles.findMostRecent(any(), anyString(), any())).thenReturn(Optional.empty());

        assertThat(service().of(Market.SPOT, "NOSUCHUSDT")).isEmpty();
    }

    /** An open of zero can only come from a corrupt bucket, but dividing by it would take
     *  the endpoint down for every caller rather than just misreporting one symbol. */
    @Test
    void doesNotDivideByAZeroOpen() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                hourly(NOW, 0, 10 * ONE, 0, 10 * ONE, ONE, 10 * ONE, 1)));

        Ticker ticker = service().of(Market.SPOT, SYMBOL).orElseThrow();

        assertThat(ticker.priceChangeBps()).isZero();
        assertThat(ticker.priceChange()).isEqualTo(10 * ONE);
    }

    /** The fallback query is a second round trip, so it must not happen when the window
     *  already answered the question. */
    @Test
    void doesNotQueryAgainWhenTheWindowHasCandles() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                hourly(NOW, 100 * ONE, 100 * ONE, 100 * ONE, 100 * ONE, ONE, 100 * ONE, 1)));

        service().of(Market.SPOT, SYMBOL);

        verify(candles, never()).findMostRecent(any(), anyString(), any());
    }

    /** Reads route by market -- a futures ticker must never be answered from spot data. */
    @Test
    void readsFromTheRequestedMarketsCollection() {
        when(candles.findRange(any(), anyString(), any(), any(), any())).thenReturn(List.of(
                hourly(NOW, 100 * ONE, 100 * ONE, 100 * ONE, 100 * ONE, ONE, 100 * ONE, 1)));

        service().of(Market.FUTURES, SYMBOL);

        verify(candles).findRange(eq(Market.FUTURES), eq(SYMBOL), eq(CandleInterval.ONE_HOUR),
                any(), any());
    }

    private static Candle hourly(Instant at, long open, long high, long low, long close,
                                 long volume, long quoteVolume, int trades) {
        return CandleFixture.candle(Market.SPOT, SYMBOL, CandleInterval.ONE_HOUR,
                CandleInterval.ONE_HOUR.bucketStart(at),
                open, high, low, close, volume, quoteVolume, trades);
    }
}
