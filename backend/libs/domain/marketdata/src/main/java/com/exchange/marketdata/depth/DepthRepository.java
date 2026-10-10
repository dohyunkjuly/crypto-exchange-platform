package com.exchange.marketdata.depth;

import com.exchange.marketdata.candle.Market;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Repository;

/**
 * Reads order books out of whichever collection the product owns.
 *
 * <p>A class over a {@code MongoRepository} interface for the same reason {@code
 * CandleRepository} is one: a Spring Data repository binds to a single collection, and
 * there is one collection per product ({@link Market#depthCollection()}). Every method
 * here takes a {@link Market} and passes the collection name explicitly, which is the only
 * thing standing between a caller and the wrong product's book -- {@link DepthBook}
 * deliberately declares no default collection name.
 *
 * <p>Reads only. A book is written by {@code market-data}'s {@code DepthWriter} through a
 * single guarded update per batch; a read-modify-write here would lose the idempotency
 * {@link DepthBook#getLastSequence()} exists to provide.
 */
@Repository
public class DepthRepository {

    private final MongoTemplate mongo;

    public DepthRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * The precomputed top of book, and nothing else.
     *
     * <p>The field projection is the whole point of this method rather than an
     * optimisation on top of it. Without it, MongoDB ships the full ladder over the wire
     * so the caller can throw away all but the first few levels -- several hundred
     * kilobytes to answer a question whose answer is a couple, several times a second per
     * watched symbol. The returned {@link DepthBook} therefore has null {@code bids} and
     * {@code asks} by design; {@link Depth} is null-tolerant for exactly this reason.
     */
    public Optional<DepthBook> findTop(Market market, String symbol) {
        Query query = new Query(Criteria.where("_id").is(DepthBook.idOf(market, symbol)));
        query.fields()
                .include("symbol")
                .include("market")
                .include("topBids")
                .include("topAsks")
                .include("lastSequence");
        return Optional.ofNullable(
                mongo.findOne(query, DepthBook.class, market.depthCollection()));
    }

    /** The whole ladder, for a request that reaches past {@link DepthBook#TOP_LEVELS}. */
    public Optional<DepthBook> findFull(Market market, String symbol) {
        return Optional.ofNullable(mongo.findById(DepthBook.idOf(market, symbol),
                DepthBook.class, market.depthCollection()));
    }
}
