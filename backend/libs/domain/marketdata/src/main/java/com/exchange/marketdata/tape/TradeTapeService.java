package com.exchange.marketdata.tape;

import com.exchange.marketdata.candle.Market;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Reading the public trade tape. Shared by web-bff (the trades panel) and spot-open-api
 * (its {@code /trades}), which is why it lives here rather than in either app -- see
 * AGENTS.md's "does more than one app need it?".
 *
 * <p>The socket carries live trades already ({@code marketws}'s {@code trade} channel), so
 * this exists for the moment a page loads: without it the panel is empty until someone
 * happens to trade, which on a quiet market is a long time and looks broken. Exactly the gap
 * {@code DepthChannel.initialFrame} closes for the order book, in REST shape.
 */
@Service
public class TradeTapeService {

    /** A cap, not a default. Reading past what a tape holds is not an error -- there simply
     *  is no more, and {@code spot_trades} is where deeper history lives. */
    public static final int MAX_TRADES = TradeTape.CAPACITY;

    private final TradeTapeRepository tapes;

    public TradeTapeService(TradeTapeRepository tapes) {
        this.tapes = tapes;
    }

    /**
     * The most recent {@code limit} trades, <b>newest first</b> -- the order a tape prints,
     * so nothing downstream has to reverse it and two callers cannot disagree about which
     * end is new.
     *
     * <p>Empty for a symbol that has never traded. That is not an error: a newly listed
     * market is perfectly valid and simply has no prints yet.
     *
     * @throws IllegalArgumentException if {@code limit} is not positive.
     */
    public List<PublicTrade> recent(Market market, String symbol, int limit) {
        int wanted = clamp(limit);
        return tapes.find(market, symbol)
                .map(TradeTape::getTrades)
                .map(stored -> newestFirst(stored, wanted))
                .orElseGet(List::of);
    }

    /** Stored oldest first, because that is the order {@code $push} appends in. Taking the
     *  tail and reversing it is what turns that into "the newest N, newest first". */
    private static List<PublicTrade> newestFirst(List<PublicTrade> stored, int wanted) {
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<PublicTrade> tail = stored.size() <= wanted
                ? stored
                : stored.subList(stored.size() - wanted, stored.size());
        return List.copyOf(tail).reversed();
    }

    private static int clamp(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        return Math.min(limit, MAX_TRADES);
    }
}
