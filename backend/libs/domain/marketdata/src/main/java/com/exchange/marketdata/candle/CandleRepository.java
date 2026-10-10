package com.exchange.marketdata.candle;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Reads candles out of whichever collection the product owns.
 *
 * <p>A class over a {@code MongoRepository} interface for one reason: a Spring Data
 * repository is bound to a single collection, and there is one collection per product
 * ({@link Market#candleCollection()}). Every method here therefore takes a {@link Market}
 * and passes the collection name explicitly -- which is also the only thing standing
 * between a caller and the wrong collection, since {@link Candle} deliberately declares no
 * default name.
 *
 * <p>Reads only. Candles are written by {@code market-data}'s own {@code CandleWriter} via
 * a single atomic upsert per bucket; a read-modify-write here would lose the idempotency
 * guarantee {@link Candle#getLastSequence()} exists to provide.
 */
@Repository
public class CandleRepository {

    private final MongoTemplate mongo;

    public CandleRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /** The chart: one interval of one symbol, oldest first within the window. */
    public List<Candle> findRange(Market market, String symbol, CandleInterval interval,
                                  Instant from, Instant to) {
        Query query = new Query(base(symbol, interval).and("openTime").gte(from).lte(to))
                .with(Sort.by(Sort.Direction.ASC, "openTime"));
        return mongo.find(query, Candle.class, market.candleCollection());
    }

    /** The most recent buckets, newest first -- for "give me the last N candles" without
     *  the caller having to know what time range that is. */
    public List<Candle> findLatest(Market market, String symbol, CandleInterval interval,
                                   int limit) {
        Query query = new Query(base(symbol, interval))
                .with(Sort.by(Sort.Direction.DESC, "openTime"))
                .limit(limit);
        return mongo.find(query, Candle.class, market.candleCollection());
    }

    /** The most recent buckets opening at or before {@code upTo}, newest first -- a chart
     *  paging back into history from the oldest bar it holds. */
    public List<Candle> findLatestUpTo(Market market, String symbol, CandleInterval interval,
                                       Instant upTo, int limit) {
        Query query = new Query(base(symbol, interval).and("openTime").lte(upTo))
                .with(Sort.by(Sort.Direction.DESC, "openTime"))
                .limit(limit);
        return mongo.find(query, Candle.class, market.candleCollection());
    }

    /** Latest close for a symbol, which is its last traded price. */
    public Optional<Candle> findMostRecent(Market market, String symbol, CandleInterval interval) {
        Query query = new Query(base(symbol, interval))
                .with(Sort.by(Sort.Direction.DESC, "openTime"))
                .limit(1);
        return mongo.find(query, Candle.class, market.candleCollection()).stream().findFirst();
    }

    public Optional<Candle> findById(Market market, String id) {
        return Optional.ofNullable(
                mongo.findById(id, Candle.class, market.candleCollection()));
    }

    private static Criteria base(String symbol, CandleInterval interval) {
        return Criteria.where("symbol").is(symbol).and("interval").is(interval);
    }
}
