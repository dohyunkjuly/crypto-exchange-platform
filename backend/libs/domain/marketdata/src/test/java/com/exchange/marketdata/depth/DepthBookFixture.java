package com.exchange.marketdata.depth;

import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builds a {@link DepthBook} the way MongoDB's mapper does.
 *
 * <p>Same reasoning as {@code CandleFixture}: the document has no public constructor and no
 * setters on purpose, because production code never assembles one -- the writer sends field
 * operators and the reader gets instances back from the mapper, populated reflectively.
 * Lives in the document's own package so its protected constructor is reachable.
 */
public final class DepthBookFixture {

    private DepthBookFixture() {
    }

    /** A book as the projected read returns it: the precomputed arrays, no full ladder. */
    public static DepthBook top(Market market, String symbol, long lastSequence,
                                List<DepthLevel> topBids, List<DepthLevel> topAsks) {
        DepthBook book = base(market, symbol, lastSequence);
        set(book, "topBids", topBids);
        set(book, "topAsks", topAsks);
        return book;
    }

    /** A book as the unprojected read returns it: the full ladder, arrays left off so a
     *  test cannot accidentally pass by reading the wrong representation. */
    public static DepthBook full(Market market, String symbol, long lastSequence,
                                 Map<String, Long> bids, Map<String, Long> asks) {
        DepthBook book = base(market, symbol, lastSequence);
        set(book, "bids", bids);
        set(book, "asks", asks);
        return book;
    }

    /** {@code levels("60000.00000000", 1_50000000L, ...)} -- price key then quantity,
     *  in the 1e8-scaled decimal-string form the document actually stores. */
    public static Map<String, Long> levels(Object... priceThenQuantity) {
        Map<String, Long> levels = new LinkedHashMap<>();
        for (int i = 0; i < priceThenQuantity.length; i += 2) {
            levels.put(String.valueOf(priceThenQuantity[i]), (Long) priceThenQuantity[i + 1]);
        }
        return levels;
    }

    private static DepthBook base(Market market, String symbol, long lastSequence) {
        DepthBook book = new DepthBook();
        set(book, "id", DepthBook.idOf(market, symbol));
        set(book, "market", market);
        set(book, "symbol", symbol);
        set(book, "lastSequence", lastSequence);
        set(book, "updatedAt", Instant.EPOCH);
        return book;
    }

    private static void set(DepthBook book, String field, Object value) {
        ReflectionTestUtils.setField(book, field, value);
    }
}
