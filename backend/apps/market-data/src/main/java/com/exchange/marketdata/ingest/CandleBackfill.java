package com.exchange.marketdata.ingest;

import com.exchange.marketdata.candle.Candle;
import com.exchange.marketdata.candle.CandleInterval;
import com.exchange.marketdata.candle.Market;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Builds the history of an interval added after a market started trading.
 *
 * <p>{@link CandleWriter} materialises every {@link CandleInterval} as trades arrive, so a value
 * added to the enum has candles from that deploy on and nothing before -- a chart on it shows
 * "no trades" for a market with a year of history. The 1m candles are a complete record of every
 * trade, and every other interval is a whole number of them (a month is whole days), so the
 * missing history is a rollup, done here once at startup for each market and interval that has
 * 1m candles and none of its own.
 *
 * <p>Runs as a lifecycle phase <b>before the Kafka listener container</b>, whose phase is {@code
 * AbstractMessageListenerContainer.DEFAULT_PHASE}. That ordering is what makes the rollup exact
 * rather than approximately right: while it runs nothing else writes candles, so the bucket it
 * writes from the 1m record is the whole bucket, and a trade arriving afterwards lands on top of
 * it through the writer's normal guarded update. Interleaving the two would let a live update be
 * overwritten by a rollup that had not seen its trade.
 *
 * <p>Only an interval with <b>no</b> candles for the symbol is rolled up. One that has some is
 * being written live and is left alone; a gap in the middle of a live interval is not this
 * class's problem and it does not try to guess at one.
 */
@Component
public class CandleBackfill implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CandleBackfill.class);

    private final MongoTemplate mongo;
    private volatile boolean running;

    public CandleBackfill(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public void start() {
        for (Market market : Market.values()) {
            String collection = market.candleCollection();
            List<String> symbols = mongo.findDistinct(
                    new Query(Criteria.where("interval").is(CandleInterval.ONE_MINUTE)),
                    "symbol", collection, Candle.class, String.class);
            for (String symbol : symbols) {
                for (CandleInterval interval : CandleInterval.values()) {
                    if (interval == CandleInterval.ONE_MINUTE
                            || mongo.exists(new Query(where(symbol, interval)), Candle.class, collection)) {
                        continue;
                    }
                    backfill(market, symbol, interval, collection);
                }
            }
        }
        running = true;
    }

    private void backfill(Market market, String symbol, CandleInterval interval, String collection) {
        Query oneMinute = new Query(where(symbol, CandleInterval.ONE_MINUTE))
                .with(Sort.by(Sort.Direction.ASC, "openTime"));
        List<Bucket> buckets;
        try (Stream<Candle> minutes = mongo.stream(oneMinute, Candle.class, collection)) {
            buckets = rollup(minutes, interval);
        }
        Instant now = Instant.now();
        for (Bucket b : buckets) {
            Update update = new Update()
                    .set("market", market).set("symbol", symbol).set("interval", interval)
                    .set("openTime", b.openTime())
                    .set("open", b.open()).set("high", b.high()).set("low", b.low()).set("close", b.close())
                    .set("volume", b.volume()).set("quoteVolume", b.quoteVolume())
                    .set("tradeCount", b.tradeCount())
                    .set("lastSequence", b.lastSequence())
                    .set("updatedAt", now);
            mongo.upsert(new Query(Criteria.where("_id").is(Candle.idOf(market, symbol, interval, b.openTime()))),
                    update, Candle.class, collection);
        }
        log.info("backfilled {} {} candles for {} from its 1m history", buckets.size(), interval.code(), symbol);
    }

    private static Criteria where(String symbol, CandleInterval interval) {
        return Criteria.where("symbol").is(symbol).and("interval").is(interval);
    }

    /** One rolled-up bucket. {@code lastSequence} is the newest trade folded in, so the writer's
     *  guard treats a later trade as new and an already-counted one as the redelivery it is. */
    public record Bucket(Instant openTime, long open, long high, long low, long close,
                         long volume, long quoteVolume, int tradeCount, long lastSequence) {
    }

    /** Folds 1m candles, oldest first, into {@code interval}'s buckets. Quiet buckets are simply
     *  absent, as they are when written live; the read side fills them flat. */
    static List<Bucket> rollup(Stream<Candle> oneMinuteOldestFirst, CandleInterval interval) {
        List<Bucket> out = new ArrayList<>();
        Bucket current = null;
        for (Candle c : (Iterable<Candle>) oneMinuteOldestFirst::iterator) {
            Instant open = interval.bucketStart(c.getOpenTime());
            if (current == null || !current.openTime().equals(open)) {
                if (current != null) {
                    out.add(current);
                }
                current = new Bucket(open, c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                        c.getVolume(), c.getQuoteVolume(), c.getTradeCount(), c.getLastSequence());
            } else {
                current = new Bucket(open, current.open(),
                        Math.max(current.high(), c.getHigh()), Math.min(current.low(), c.getLow()), c.getClose(),
                        current.volume() + c.getVolume(), current.quoteVolume() + c.getQuoteVolume(),
                        current.tradeCount() + c.getTradeCount(),
                        Math.max(current.lastSequence(), c.getLastSequence()));
            }
        }
        if (current != null) {
            out.add(current);
        }
        return out;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before the listener container (its DEFAULT_PHASE is near Integer.MAX_VALUE): see the class doc. */
    @Override
    public int getPhase() {
        return 0;
    }
}
