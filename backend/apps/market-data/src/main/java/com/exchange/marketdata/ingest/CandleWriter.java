package com.exchange.marketdata.ingest;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Turns a batch of trades into candle updates.
 *
 * <p><b>The whole design exists to survive Kafka's at-least-once delivery.</b> Volume is
 * accumulated, so applying the same trade twice inflates it -- with no error, no
 * exception, just a chart that quietly disagrees with the trade tape. Every guard here is
 * about that one failure.
 *
 * <p>Three steps, mirroring how {@code OrderEffectService} handles the same problem on the
 * settlement side:
 * <ol>
 *   <li><b>Read the watermarks.</b> One query fetches every bucket this batch touches, so
 *       we learn each one's {@code lastSequence} without a round trip per trade.
 *   <li><b>Filter and aggregate in memory.</b> Trades at or below a bucket's watermark
 *       were already applied and are dropped; the rest are folded into one accumulator
 *       per bucket. This is what collapses a 2,000-trade batch into a handful of writes:
 *       one per (symbol, interval, bucket), not one per trade per interval.
 *   <li><b>One guarded upsert per bucket.</b> The filter carries {@code lastSequence <
 *       newSequence} as well, so even if step 1's read raced another writer, the update
 *       cannot apply twice.
 * </ol>
 *
 * <p>Step 1's read-then-write is safe without locking because a symbol's events always
 * land on one partition, and a partition is owned by exactly one consumer in the group --
 * so one symbol's buckets have exactly one writer at a time. Step 3's condition is the
 * backstop for the brief overlap a rebalance can create.
 *
 * <p>Sequences are per symbol (see {@code events.proto}), so watermarks are only ever
 * compared within one symbol's own buckets -- never across symbols.
 */
@Component
public class CandleWriter {

    private final MongoTemplate mongo;

    public CandleWriter(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** One trade, already parsed off the wire. Prices and quantities are 1e8-scaled. */
    public record TradeTick(Market market, String symbol, long sequence, Instant executedAt,
                            long price, long quantity, long quoteQuantity) {
    }

    /**
     * Applies every tick that has not already been applied. Safe to call twice with the
     * same input -- the second call finds every bucket's watermark already at or past
     * these sequences and writes nothing.
     */
    public void apply(List<TradeTick> ticks) {
        if (ticks.isEmpty()) {
            return;
        }

        Map<BucketKey, Accumulator> buckets = accumulate(ticks, readWatermarks(ticks));
        for (Map.Entry<BucketKey, Accumulator> entry : buckets.entrySet()) {
            write(entry.getKey(), entry.getValue());
        }
    }

    /** Every bucket id this batch could touch, looked up in one query. An id with no row
     *  simply has no entry -- that bucket does not exist yet, so nothing has been applied
     *  to it. */
    private Map<String, Long> readWatermarks(List<TradeTick> ticks) {
        Map<String, Set<String>> idsByCollection = new java.util.LinkedHashMap<>();
        for (TradeTick tick : ticks) {
            for (CandleInterval interval : CandleInterval.values()) {
                idsByCollection
                        .computeIfAbsent(tick.market().candleCollection(), k -> new HashSet<>())
                        .add(Candle.idOf(tick.market(), tick.symbol(), interval,
                                interval.bucketStart(tick.executedAt())));
            }
        }
        // One query per product touched -- in practice one, since a consumer reads one
        // product's topic. Each product's candles live in their own collection.
        Map<String, Long> watermarks = new java.util.HashMap<>();
        idsByCollection.forEach((collection, collectionIds) ->
                mongo.find(new Query(Criteria.where("_id").in(collectionIds)),
                                Candle.class, collection)
                        .forEach(c -> watermarks.put(c.getId(), c.getLastSequence())));
        return watermarks;
    }

    /** Folds the ticks into one accumulator per bucket, dropping anything a bucket has
     *  already counted. Insertion order is preserved so {@code open}/{@code close} come
     *  from the first and last tick actually applied -- correct because a partition
     *  delivers one symbol's events in sequence order. */
    private Map<BucketKey, Accumulator> accumulate(List<TradeTick> ticks,
                                                   Map<String, Long> watermarks) {
        Map<BucketKey, Accumulator> buckets = new LinkedHashMap<>();
        for (TradeTick tick : ticks) {
            for (CandleInterval interval : CandleInterval.values()) {
                Instant openTime = interval.bucketStart(tick.executedAt());
                String id = Candle.idOf(tick.market(), tick.symbol(), interval, openTime);

                Long applied = watermarks.get(id);
                if (applied != null && tick.sequence() <= applied) {
                    continue; // already counted in this bucket -- a redelivery
                }
                buckets.computeIfAbsent(
                        new BucketKey(tick.market(), tick.symbol(), interval, openTime, id),
                        key -> new Accumulator()).add(tick);
            }
        }
        return buckets;
    }

    /**
     * One atomic update per bucket.
     *
     * <p>{@code $setOnInsert} for {@code open}, because a bucket's opening price is
     * whatever its first trade was -- on a later batch the bucket already exists and must
     * keep it. {@code $max}/{@code $min} for the extremes and {@code $inc} for the totals,
     * so this composes correctly with whatever is already stored. {@code $set} for
     * {@code close}, which is by definition the most recent.
     */
    private void write(BucketKey key, Accumulator acc) {
        Query query = new Query(Criteria.where("_id").is(key.id())
                .orOperator(Criteria.where("lastSequence").lt(acc.lastSequence),
                        Criteria.where("lastSequence").exists(false)));

        Update update = new Update()
                .setOnInsert("market", key.market())
                .setOnInsert("symbol", key.symbol())
                .setOnInsert("interval", key.interval())
                .setOnInsert("openTime", key.openTime())
                .setOnInsert("open", acc.open);


        update
                .max("high", acc.high)
                .min("low", acc.low)
                .inc("volume", acc.volume)
                .inc("quoteVolume", acc.quoteVolume)
                .inc("tradeCount", acc.tradeCount)
                .set("close", acc.close)
                .set("lastSequence", acc.lastSequence)
                .set("updatedAt", Instant.now());

        mongo.upsert(query, update, Candle.class, key.market().candleCollection());
    }

    private record BucketKey(Market market, String symbol, CandleInterval interval,
                             Instant openTime, String id) {
    }

    private static final class Accumulator {
        private long open;
        private long high = Long.MIN_VALUE;
        private long low = Long.MAX_VALUE;
        private long close;
        private long volume;
        private long quoteVolume;
        private int tradeCount;
        private long lastSequence;
        private boolean seeded;

        void add(TradeTick tick) {
            if (!seeded) {
                open = tick.price();
                seeded = true;
            }
            high = Math.max(high, tick.price());
            low = Math.min(low, tick.price());
            close = tick.price();
            volume += tick.quantity();
            quoteVolume += tick.quoteQuantity();
            tradeCount++;
            lastSequence = Math.max(lastSequence, tick.sequence());
        }
    }
}
