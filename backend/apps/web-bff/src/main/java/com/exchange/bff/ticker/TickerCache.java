package com.exchange.bff.ticker;

import com.exchange.spot.symbol.MarketSymbol;
import com.exchange.spot.symbol.SymbolCatalog;
import com.exchange.marketdata.candle.Market;
import com.exchange.marketdata.ticker.Ticker;
import com.exchange.marketdata.ticker.TickerService;
import com.exchange.marketdata.ticker.TickerSnapshot;
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
 * Every market's 24h summary, recomputed on a schedule and served from memory.
 *
 * <p><b>Why this exists.</b> {@code TickerService}'s own javadoc says it: "Callers should
 * cache the result for a few seconds; it is a summary, not a quote." A markets list is
 * polled by every open browser every few seconds, and each symbol costs a candle range
 * query -- so uncached, database load is {@code symbols × browsers ÷ poll interval} and
 * grows with the audience. Cached, it is {@code symbols ÷ refresh interval}: the same work
 * whether one person is watching or a thousand.
 *
 * <p>That is the same reasoning as {@code DepthPublisher} and deliberately <em>not</em> the
 * reasoning applied to {@code GET /{symbol}/depth}, which reads through. The difference is
 * how requests scale: a page load happens once per user, while polling happens forever, so
 * only the second one is worth putting a cache in front of.
 *
 * <p>Every visible market appears, including one that has never traded -- it reports zeros
 * rather than being omitted. A newly listed market that is missing from the list looks to a
 * user like a market that does not exist.
 *
 * <p>Holds domain {@link Ticker}s and implements the shared {@link TickerSnapshot} the socket's
 * ticker channel reads; the REST controller renders its view from the same list.
 */
@Component
public class TickerCache implements TickerSnapshot {

    private static final Logger log = LoggerFactory.getLogger(TickerCache.class);

    private final SymbolCatalog catalog;
    private final TickerService tickers;

    /** Replaced wholesale, never mutated in place, so a reader always sees one consistent
     *  set rather than a list being rebuilt underneath it. */
    private volatile List<Ticker> snapshot = List.of();

    public TickerCache(SymbolCatalog catalog, TickerService tickers) {
        this.catalog = catalog;
        this.tickers = tickers;
    }

    /** The latest computed summaries, busiest market first. Empty only in the moment
     *  between the context starting and the first refresh finishing. */
    @Override
    public List<Ticker> all() {
        return snapshot;
    }

    /**
     * One market's summary from the same snapshot, or empty if it is not listed or has not
     * been computed yet.
     *
     * <p>What the WebSocket's ticker channel reads. A linear scan rather than a map, because
     * the snapshot is a few dozen entries and keeping both would mean two things to rebuild
     * on every refresh and one more way for them to disagree.
     */
    @Override
    public Optional<Ticker> of(String symbol) {
        return snapshot.stream()
                .filter(ticker -> ticker.symbol().equals(symbol))
                .findFirst();
    }

    /**
     * {@code fixedDelay}, so a slow refresh spaces the next one from its finish instead of
     * stacking runs behind it. Two seconds because this is a summary of whole hourly
     * buckets -- refreshing faster would recompute the same numbers.
     */
    @Scheduled(fixedDelayString = "${exchange.market.ticker-refresh-ms:2000}")
    public void refresh() {
        try {
            snapshot = build();
        } catch (RuntimeException e) {
            // Keep serving the previous snapshot. A markets list a few seconds stale is far
            // better than an empty one, and the next tick retries in two seconds.
            log.warn("could not refresh the ticker snapshot; serving the previous one", e);
        }
    }

    private List<Ticker> build() {
        List<MarketSymbol> markets = catalog.visible();
        List<Ticker> computed = new ArrayList<>(markets.size());
        for (MarketSymbol market : markets) {
            computed.add(tickers.of(Market.SPOT, market.symbol())
                    .orElseGet(() -> neverTraded(market.symbol())));
        }

        // Busiest first, in quote terms so markets are comparable across base assets --
        // the order a markets list opens in before anyone sorts it. Sorted on the domain
        // record, whose volume is a 1e8-scaled long, rather than on the view, whose volume
        // is a decimal string: ordering those would mean parsing money back into a double,
        // which is the one thing the scaling exists to prevent.
        computed.sort(Comparator.comparingLong(Ticker::quoteVolume).reversed());

        return List.copyOf(computed);
    }

    /** A listed market with no candles at all. Zeros rather than absence: the market is
     *  real and tradable, it simply has no history to summarise yet. */
    private static Ticker neverTraded(String symbol) {
        Instant now = Instant.now();
        return Ticker.flat(symbol, 0L, now, now);
    }
}
