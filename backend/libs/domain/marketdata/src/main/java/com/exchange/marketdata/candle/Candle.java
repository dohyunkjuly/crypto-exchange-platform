package com.exchange.marketdata.candle;

import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One OHLCV bucket: what one symbol did over one slice of time.
 *
 * <p>A projection, not a record -- {@code spot_trades} is the truth and every candle can be
 * rebuilt by replaying {@code exchange.spot.events.v1}. Never read a balance, fill or fee here.
 *
 * <p>Prices and quantities are 1e8-scaled longs (AGENTS.md), not {@code double}: a chart that
 * disagrees with the tape in the seventh decimal is a support ticket, and Mongo makes storing a
 * double far too easy by accident.
 *
 * <p><b>Nothing here expires.</b> There was an {@code expiresAt} and a TTL index; both are
 * gone -- see {@link CandleInterval}. A candle is the only copy this app can serve, since
 * {@code market-data} holds no MySQL connection and cannot rebuild one from {@code spot_trades}.
 *
 * <p>{@code lastSequence} is what makes writing safe under at-least-once delivery: volume is
 * accumulated with {@code $inc}, so applying a trade twice would silently inflate it. The
 * writer only applies a strictly greater sequence and advances it in the same update -- stored
 * in this document, not beside it, so MongoDB's single-document atomicity keeps the volume and
 * the watermark that explains it together.
 */
// No collection name here on purpose: there is one collection per product
// (Market#candleCollection), so every read and write passes the name explicitly. Nothing
// should ever call a MongoTemplate method that defaults the collection from this class --
// go through CandleRepository, which always routes by Market.
@Document
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Candle {

    /**
     * {@code market:SYMBOL:interval:bucketStartMillis} -- deterministic on purpose.
     *
     * <p>The writer can then upsert straight to a known id with no lookup first, and the
     * id itself is the uniqueness constraint, so no separate compound index is needed to
     * stop the same bucket existing twice.
     */
    @Id
    private String id;

    /** Which product this symbol belongs to. Part of the id because a symbol name is
     *  only unique within a product -- see {@link Market}. */
    private Market market;

    private String symbol;
    private CandleInterval interval;

    /** Bucket start, always {@code interval.bucketStart(...)} of a trade's time. */
    private Instant openTime;

    /** 1e8-scaled. First, highest, lowest and most recent trade price in the bucket. */
    private long open;
    private long high;
    private long low;
    private long close;

    /** 1e8-scaled base-asset quantity traded in the bucket. */
    private long volume;

    /** 1e8-scaled quote-asset value traded -- carried rather than derived, because a
     *  bucket's average price is not its close and cannot be recovered afterwards. */
    private long quoteVolume;

    private int tradeCount;

    /** The idempotency watermark -- see this class's own javadoc. */
    private long lastSequence;

    private Instant updatedAt;

    public static String idOf(Market market, String symbol, CandleInterval interval,
                             Instant openTime) {
        return market.code() + ":" + symbol + ":" + interval.code() + ":"
                + openTime.toEpochMilli();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Candle other)) {
            return false;
        }
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getClass());
    }
}
