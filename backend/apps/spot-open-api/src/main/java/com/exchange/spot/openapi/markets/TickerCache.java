package com.exchange.spot.openapi.markets;

import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ticker.Ticker;
import com.exchange.marketdata.ticker.TickerService;
import com.exchange.marketdata.ticker.TickerSnapshot;
import com.exchange.spot.symbol.MarketSymbol;
import com.exchange.spot.symbol.SymbolCatalog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every market's 24h summary, recomputed on a schedule and served from memory -- the same
 * reasoning as web-bff's {@code TickerCache}: the all-markets list is polled forever, and each
 * symbol costs a candle range query, so the work is done once per interval rather than once per
 * caller. This copy holds domain {@link Ticker}s rather than a view. The two caches merge into
 * {@code libs/domain/marketdata} with the publisher move (design doc, build step 5).
 *
 * <p>A listed market that has never traded appears with zeros rather than being omitted.
 */
@Component
public class TickerCache implements TickerSnapshot {

    private static final Logger log = LoggerFactory.getLogger(TickerCache.class);

    private final SymbolCatalog catalog;
    private final TickerService tickers;
    private volatile List<Ticker> snapshot = List.of();

    public TickerCache(SymbolCatalog catalog, TickerService tickers) {
        this.catalog = catalog;
        this.tickers = tickers;
    }

    /** Busiest market first, by quote volume. Empty only before the first refresh finishes. */
    @Override
    public List<Ticker> all() {
        return snapshot;
    }

    @Override
    public Optional<Ticker> of(String symbol) {
        return snapshot.stream().filter(t -> t.symbol().equals(symbol)).findFirst();
    }

    @Scheduled(fixedDelayString = "${exchange.market.ticker-refresh-ms:2000}")
    public void refresh() {
        try {
            List<Ticker> computed = new ArrayList<>();
            for (MarketSymbol market : catalog.visible()) {
                computed.add(tickers.of(Market.SPOT, market.symbol())
                        .orElseGet(() -> Ticker.flat(market.symbol(), 0L, Instant.now(), Instant.now())));
            }
            computed.sort(Comparator.comparingLong(Ticker::quoteVolume).reversed());
            snapshot = List.copyOf(computed);
        } catch (RuntimeException e) {
            log.warn("could not refresh the ticker snapshot; serving the previous one", e);
        }
    }
}
