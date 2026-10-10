package com.exchange.bff.ticker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exchange.spot.symbol.MarketSymbol;
import com.exchange.spot.symbol.SymbolCatalog;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ticker.Ticker;
import com.exchange.marketdata.ticker.TickerService;
import com.exchange.spot.symbol.SymbolStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The cache's own decisions, with both collaborators mocked.
 *
 * <p>A unit test rather than a slice of {@code MarketListIT} for one reason worth stating:
 * ordering and the never-traded fallback both depend on what the data happens to contain,
 * and the development database's markets all have the same (zero) volume. An integration
 * assertion on ordering there passes whether the sort is right, backwards, or absent --
 * verified by reversing the comparator and watching it still go green. Fixed volumes here
 * make the same assertion mean something.
 */
@ExtendWith(MockitoExtension.class)
class TickerCacheTest {

    private static final long ONE = 100_000_000L;

    @Mock
    private SymbolCatalog catalog;

    @Mock
    private TickerService tickers;

    private TickerCache cache() {
        return new TickerCache(catalog, tickers);
    }

    @Test
    void ordersMarketsByQuoteVolumeBusiestFirst() {
        when(catalog.visible()).thenReturn(List.of(
                market("AAAUSDT"), market("BBBUSDT"), market("CCCUSDT")));
        // Alphabetical order is deliberately the opposite of volume order, so a cache that
        // simply preserved the catalog's order would fail rather than pass by coincidence.
        stub("AAAUSDT", 5 * ONE);
        stub("BBBUSDT", 900 * ONE);
        stub("CCCUSDT", 40 * ONE);

        TickerCache cache = cache();
        cache.refresh();

        assertThat(cache.all()).extracting(Ticker::symbol)
                .containsExactly("BBBUSDT", "CCCUSDT", "AAAUSDT");
    }

    /** Omitting a market with no trades looks to a user exactly like a market that does not
     *  exist -- and a newly listed pair is the one people go looking for. */
    @Test
    void aMarketWithNoHistoryStillAppearsWithZeros() {
        when(catalog.visible()).thenReturn(List.of(market("NEWUSDT"), market("OLDUSDT")));
        when(tickers.of(Market.SPOT, "NEWUSDT")).thenReturn(Optional.empty());
        stub("OLDUSDT", 10 * ONE);

        TickerCache cache = cache();
        cache.refresh();

        assertThat(cache.all()).extracting(Ticker::symbol)
                .containsExactlyInAnyOrder("NEWUSDT", "OLDUSDT");
        assertThat(cache.all()).filteredOn(t -> t.symbol().equals("NEWUSDT"))
                .singleElement()
                .returns(0L, Ticker::quoteVolume)
                .returns(0L, Ticker::lastPrice);
    }

    /**
     * A markets list a few seconds stale beats an empty one, and the next tick retries.
     * Letting the exception through would leave every open browser with a blank panel
     * because one query timed out.
     */
    @Test
    void aFailedRefreshKeepsServingThePreviousSnapshot() {
        when(catalog.visible()).thenReturn(List.of(market("AAAUSDT")));
        stub("AAAUSDT", 5 * ONE);

        TickerCache cache = cache();
        cache.refresh();
        List<Ticker> good = cache.all();

        when(catalog.visible()).thenThrow(new IllegalStateException("mongo is down"));
        cache.refresh();

        assertThat(cache.all()).isSameAs(good);
    }

    /** The entire point: reads are free, so database load is symbols per refresh rather
     *  than symbols per poll per browser. */
    @Test
    void readingDoesNotRecompute() {
        when(catalog.visible()).thenReturn(List.of(market("AAAUSDT")));
        stub("AAAUSDT", 5 * ONE);

        TickerCache cache = cache();
        cache.refresh();
        cache.all();
        cache.all();

        verify(tickers).of(Market.SPOT, "AAAUSDT");
    }

    @Test
    void beforeTheFirstRefreshTheListIsEmptyRatherThanNull() {
        TickerCache cache = cache();

        assertThat(cache.all()).isEmpty();
        verify(catalog, never()).visible();
    }

    // --- helpers ---

    private void stub(String symbol, long quoteVolume) {
        when(tickers.of(Market.SPOT, symbol)).thenReturn(Optional.of(new Ticker(
                symbol, 100 * ONE, 110 * ONE, 90 * ONE, 105 * ONE, 5 * ONE, 500,
                quoteVolume / 100, quoteVolume, 7,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(86_400))));
    }

    private static MarketSymbol market(String symbol) {
        return new MarketSymbol(symbol, symbol.substring(0, 3), "USDT", SymbolStatus.TRADING,
                ONE / 100, ONE / 100_000, ONE / 1000, 9000 * ONE, 10 * ONE, null, null, null, null, null, 8, 8, true, true,
                List.of(ONE / 100, ONE / 10), 0);
    }
}
