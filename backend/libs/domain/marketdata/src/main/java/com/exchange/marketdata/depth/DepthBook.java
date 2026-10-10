package com.exchange.marketdata.depth;

import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One symbol's order book: every price level with something resting on it.
 *
 * <p>A projection, not a record. The engine's book is the truth and lives in its RAM; this is
 * folded from {@code exchange.spot.events.v1} and can be thrown away and rebuilt by replaying
 * it. Never read a balance, an order or a fill from here.
 *
 * <p><b>The book is stored twice, deliberately.</b> {@link #getBids()}/{@link #getAsks()} are
 * the full ladder and the durable state; {@link #getTopBids()}/{@link #getTopAsks()} are the
 * first {@link #TOP_LEVELS}, precomputed in the same update. A trading page's ~20 visible
 * levels refresh several times a second per viewer -- served from the maps, each read would
 * pull hundreds of kilobytes to render two. They cannot drift: MongoDB's single-document
 * atomicity moves both representations and the watermark together or not at all.
 *
 * <p><b>Map keys are the 1e8-scaled price as a decimal string</b> -- a long, so the key never
 * contains {@code '.'}, which MongoDB forbids in field names. Lexicographic order over these
 * keys is not price order; {@link Depth} sorts numerically.
 *
 * <p>An empty level is <b>removed</b>, never stored as zero, or the maps accumulate an entry
 * for every price the market has ever touched and nothing ever deletes them.
 *
 * <p>{@code lastSequence} is the idempotency watermark, and it matters more here than for
 * candles: depth is built from deltas, so an event applied twice under at-least-once delivery
 * leaves a level permanently wrong with no error. The writer only applies events past it, and
 * advances it in the same atomic update.
 *
 * <p>No TTL and no index beyond {@code _id} -- current state, not history, read by
 * deterministic id.
 */
// No collection name here on purpose, exactly as Candle does it: there is one collection
// per product (Market#depthCollection), so every read and write passes the name
// explicitly. Go through DepthRepository, which always routes by Market.
@Document
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepthBook {

    /**
     * How many levels per side the writer precomputes into {@link #getTopBids()}/{@link
     * #getTopAsks()}.
     *
     * <p>Comfortably above the ~20 a trading page shows, so the cheap read covers the
     * common case with room to spare, and small enough that the arrays stay a rounding
     * error next to the maps they summarise. Shared with the writer, which must fill
     * exactly this many -- a reader asking for more than this falls back to the full
     * ladder, so the two numbers disagreeing would silently make every read expensive.
     */
    public static final int TOP_LEVELS = 50;

    /** {@code market:SYMBOL} -- deterministic, so the writer upserts to a known id with no
     *  lookup first and the id itself is the uniqueness constraint. */
    @Id
    private String id;

    /** Which product this symbol belongs to. Part of the id because a symbol name is only
     *  unique within a product -- {@code BTCUSDT} is both a spot market and a perpetual. */
    private Market market;

    private String symbol;

    /** Price -> resting quantity, both 1e8-scaled; see this class's javadoc on key format.
     *  Unordered as stored: {@link Depth} sorts on read. */
    private Map<String, Long> bids;

    private Map<String, Long> asks;

    /** The best {@link #TOP_LEVELS} bids, highest price first. */
    private List<DepthLevel> topBids;

    /** The best {@link #TOP_LEVELS} asks, lowest price first. */
    private List<DepthLevel> topAsks;

    /** The idempotency watermark -- see this class's own javadoc. */
    private long lastSequence;

    private Instant updatedAt;

    public static String idOf(Market market, String symbol) {
        return market.code() + ":" + symbol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DepthBook other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
