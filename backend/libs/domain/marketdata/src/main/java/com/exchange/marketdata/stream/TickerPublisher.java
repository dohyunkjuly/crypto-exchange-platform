package com.exchange.marketdata.stream;

import com.exchange.marketdata.ticker.Ticker;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes each watched market's 24h summary to everyone watching it.
 *
 * <p>The cheapest of the three publishers by a wide margin: {@code TickerCache} has already
 * recomputed every market for the REST markets list, so a tick here is a map lookup and a
 * fan-out. No MongoDB read, at any subscriber count.
 *
 * <p>Which means the interval is bounded by something other than cost -- there is no point
 * publishing faster than the cache refreshes, since the numbers would be identical. Matching
 * it is the most this can usefully do.
 *
 * <p><b>An unchanged summary sends nothing.</b> The comparison is on the last price and the
 * window it was computed over, because those are what move; a market with no trades in a
 * refresh produces exactly the same view, and resending it is bytes for no information. New
 * subscribers do not wait on that: {@link TickerChannel#initialFrame} hands them the current
 * summary at subscribe time.
 */
@Component
public class TickerPublisher {

    private final TickerChannel channel;
    private final MarketFanout sockets;

    /** Symbol -&gt; the summary last broadcast from this instance, as its own value. Compared
     *  rather than kept as a sequence because a ticker has none -- it is computed from a
     *  window, not applied from an event. */
    private final Map<String, Ticker> published = new ConcurrentHashMap<>();

    public TickerPublisher(TickerChannel channel, MarketFanout sockets) {
        this.channel = channel;
        this.sockets = sockets;
    }

    @Scheduled(fixedDelayString = "${exchange.market-ws.ticker-interval-ms:2000}")
    public void publish() {
        Set<Subscription> watched = sockets.watched(TickerChannel.NAME);
        Set<String> symbols = watched.stream()
                .map(Subscription::symbol)
                .collect(java.util.stream.Collectors.toSet());
        // Forget markets nobody here is watching any more, so this cannot grow to hold every
        // symbol this instance has ever served.
        published.keySet().retainAll(symbols);

        for (Subscription subscription : watched) {
            channel.current(subscription.symbol())
                    .filter(ticker -> hasChanged(subscription.symbol(), ticker))
                    .ifPresent(ticker -> {
                        published.put(subscription.symbol(), ticker);
                        sockets.broadcast(subscription, TickerChannel.frameOf(ticker));
                    });
        }
    }

    /** Value equality on the whole view. Comparing only the last price would miss a volume
     *  that moved on a trade at an unchanged price, which is common on a thin market. */
    private boolean hasChanged(String symbol, Ticker ticker) {
        return !ticker.equals(published.get(symbol));
    }
}
