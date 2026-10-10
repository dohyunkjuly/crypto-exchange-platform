package com.exchange.marketdata.testsupport;

import com.exchange.marketdata.candle.Market;
import java.util.Collection;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Deletes the candles and books a test built, so runs do not silently accumulate in the
 * shared development database.
 *
 * <p>MongoDB's half of what {@code SeededDataCleaner} does for MySQL in spot-settlement and
 * web-bff, and it exists for the same reason theirs does: they did accumulate. A survey of
 * the development database found 584,995 orphaned rows in MySQL and a few hundred stray
 * documents here, none of it from anything but test runs.
 *
 * <p>The stakes are lower on this side but not zero. These collections back the candle
 * benchmarks, which measure how fast a batch is folded and written -- against a collection
 * whose size was being set by however many times anyone had run them before. A measurement
 * that drifts with your own history is worse than no measurement, because it looks like a
 * result.
 *
 * <p>Symbols are the only handle needed: every document in either collection carries one,
 * and tests invent unique ones. Every product's collections are swept, not just spot's, so
 * a futures test added later is covered without anyone remembering this class exists.
 *
 * <p>Deliberately not shared with the {@code SeededDataCleaner}s in the other apps. That is
 * the same trade those two already make with each other -- test-only code in separate
 * modules, where the alternative is a testFixtures source set and the wiring to consume it,
 * for classes that clean up different databases and share no logic.
 */
public final class SeededMarketData {

    private SeededMarketData() {
    }

    public static void purge(ConfigurableApplicationContext ctx, Collection<String> symbols) {
        purge(ctx.getBean(MongoTemplate.class), symbols);
    }

    public static void purge(ConfigurableApplicationContext ctx, String symbol) {
        purge(ctx, List.of(symbol));
    }

    /**
     * Removes every candle and book for these symbols, in every product.
     *
     * <p>Never throws. This runs in a {@code finally} or an {@code @AfterEach}, where an
     * exception would replace the real failure with a cleanup failure -- and a test that
     * genuinely broke is far more interesting than the rows it left behind.
     */
    public static void purge(MongoTemplate mongo, Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return;
        }
        Query bySymbol = new Query(Criteria.where("symbol").in(symbols));
        for (Market market : Market.values()) {
            remove(mongo, bySymbol, market.candleCollection());
            remove(mongo, bySymbol, market.depthCollection());
        }
    }

    private static void remove(MongoTemplate mongo, Query query, String collection) {
        try {
            mongo.remove(query, collection);
        } catch (RuntimeException e) {
            System.err.println("could not purge " + collection + ": " + e.getMessage());
        }
    }
}
