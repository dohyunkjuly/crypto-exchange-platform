package com.exchange.marketdata.tape;

import com.exchange.marketdata.candle.Market;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads trade tapes out of whichever collection the product owns.
 *
 * <p>A class rather than a {@code MongoRepository} interface, for the reason
 * {@code CandleRepository} and {@code DepthRepository} give: a Spring Data repository binds
 * to one collection, and there is one per product ({@link Market#tapeCollection()}).
 *
 * <p>No field projection here, unlike {@code DepthRepository#findTop}. The whole document is
 * the answer -- it is bounded at {@link TradeTape#CAPACITY} by construction, so there is no
 * large half to leave behind.
 *
 * <p>Reads only. A tape is written by {@code market-data}'s {@code TradeTapeWriter} through
 * one guarded update; a read-modify-write here would lose the idempotency
 * {@link TradeTape#getLastSequence()} exists to provide.
 */
@Repository
public class TradeTapeRepository {

    private final MongoTemplate mongo;

    public TradeTapeRepository(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Optional<TradeTape> find(Market market, String symbol) {
        return Optional.ofNullable(mongo.findById(TradeTape.idOf(market, symbol),
                TradeTape.class, market.tapeCollection()));
    }
}
