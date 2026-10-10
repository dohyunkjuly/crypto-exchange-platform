package com.exchange.marketdata.ticker;

import java.util.List;
import java.util.Optional;

/** Every market's 24h summary, recomputed on a timer and served from memory. Each app keeps its
 *  own implementation because the listing it iterates comes from a module this one cannot see;
 *  the ticker channel reads through this so it adds no database work of its own. */
public interface TickerSnapshot {

    /** Busiest market first. Empty only before the first refresh finishes. */
    List<Ticker> all();

    /** One market's summary from the same snapshot, or empty if it is not listed or not computed yet. */
    Optional<Ticker> of(String symbol);
}
